package com.twitter.libkestrel

import com.twitter.logging.Logger
import com.twitter.util._
import java.io.{File, IOException}
import scala.annotation.tailrec
import scala.collection.immutable
import scala.collection.mutable

/*
 *
 * journal TODO:
 *   - rotate to new file after X
 *   X checkpoint reader
 *   X read-behind pointer for reader
 *   - clean up old files if they're dead
 */


object Journal {
  def getQueueNamesFromFolder(path: File): Set[String] = {
    path.list().filter { name =>
      !(name contains "~~")
    }.map { name =>
      name.split('.')(0)
    }.toSet
  }
}

/**
 * Maintain a set of journal files with the same prefix (`queuePath`/`queueName`):
 * - list of adds (<prefix>.<timestamp>)
 * - one state file for each reader (<prefix>.read.<name>)
 */
class Journal(queuePath: File, queueName: String, timer: Timer, syncJournal: Duration) {
  private[this] val log = Logger.get(getClass)

  val prefix = new File(queuePath, queueName)

  @volatile var idMap = immutable.TreeMap.empty[Long, File]
  @volatile var readerMap = immutable.HashMap.empty[String, Reader]

  buildIdMap()
  buildReaderMap()

  /**
   * Scan timestamp files for this queue, and build a map of (item id -> file) for the first id
   * seen in each file. This lets us quickly find the right file when we look for an item id.
   */
  private[this] def buildIdMap() {
    var newMap = immutable.TreeMap.empty[Long, File]
    writerFiles().foreach { file =>
      try {
        val j = JournalFile.openWriter(file, timer, Duration.MaxValue)
        j.readNext() match {
          case Some(JournalFile.Record.Put(item)) => {
            newMap = newMap + (item.id -> file)
          }
          case _ =>
        }
      } catch {
        case e: IOException => log.warning("Skipping corrupted file: %s", file)
      }
    }
    idMap = newMap
  }

  def writerFiles() = {
    queuePath.list().filter { name =>
      name.startsWith(queueName + ".") &&
        !name.contains("~") &&
        !name.split("\\.")(1).find { !_.isDigit }.isDefined
    }.map { name =>
      new File(queuePath, name)
    }
  }

  def readerFiles() = {
    queuePath.list().filter { name =>
      name.startsWith(queueName + ".read.") && !name.contains("~")
    }.map { name =>
      new File(queuePath, name)
    }
  }

  def fileForId(id: Long): Option[File] = {
    idMap.to(id).lastOption.map { case (k, v) => v }
  }

  private[this] def buildReaderMap() {
    var newMap = immutable.HashMap.empty[String, Reader]
    readerFiles().foreach { file =>
      val name = file.getName.split("\\.")(2)
      try {
        val reader = new Reader(file)
        reader.readState()
        newMap = newMap + (name -> reader)
      } catch {
        case e: IOException => log.warning("Skipping corrupted reader file: %s", file)
      }
    }
    readerMap = newMap
  }

  // need to pass in a "head" in case we need to make a new reader.
  def reader(name: String, head: Long): Reader = {
    readerMap.get(name).getOrElse {
      // grab a lock so only one thread does this potentially slow thing at once
      synchronized {
        readerMap.get(name).getOrElse {
          val reader = new Reader(new File(queuePath, queueName + ".read." + name))
          reader.head = head
          readerMap = readerMap + (name -> reader)
          reader
        }
      }
    }
  }

  def calculateArchiveSize() = {
    writerFiles().foldLeft(0L) { (sum, file) => sum + file.length() }
  }

  def close() {
    // FIXME
  }

  def checkpoint() {
    readerMap.foreach { case (name, reader) =>
      reader.checkpoint()
    }
  }

  /**
   * Track state for a queue reader. Every item prior to the "head" pointer (including the "head"
   * pointer itself) has been read by this reader. Separately, "doneSet" is a set of items that
   * have been read out of order, usually because they refer to transactional reads that were
   * confirmed out of order.
   */
  case class Reader(file: File) {
    private[this] var _head = 0L
    private[this] val _doneSet = new ItemIdList()
    private[this] var _readBehind: Option[JournalFile] = None
    private[this] var _readBehindId = 0L

    def readState() {
      val journalFile = JournalFile.openReader(file, timer, syncJournal)
      try {
        journalFile.foreach { entry =>
          entry match {
            case JournalFile.Record.ReadHead(id) => _head = id
            case JournalFile.Record.ReadDone(ids) => _doneSet.add(ids)
            case x => log.warning("Skipping unknown entry %s in read journal: %s", x, file)
          }
        }
      } finally {
        journalFile.close()
      }
    }

    /**
     * Rewrite the reader file with the current head and out-of-order committed reads.
     */
    def checkpoint() {
      val newFile = new File(file.getParent, file.getName + "~~")
      val newJournalFile = JournalFile.createReader(newFile, timer, syncJournal)
      newJournalFile.readHead(_head)
      newJournalFile.readDone(_doneSet.toSeq)
      newJournalFile.close()
      newFile.renameTo(file)
    }

    def head: Long = this._head
    def doneSet: Set[Long] = _doneSet.toSeq.toSet

    def head_=(id: Long) {
      this._head = id
      val toRemove = _doneSet.toSeq.filter { _ <= _head }
      _doneSet.remove(toRemove.toSet)
    }

    def commit(id: Long) {
      if (id == _head + 1) {
        _head += 1
        while (_doneSet contains _head + 1) {
          _head += 1
          _doneSet.remove(_head)
        }
      } else {
        _doneSet.add(id)
      }
    }

    /**
     * Open the journal file containing a given item, so we can read items directly out of the
     * file. This means the queue no longer wants to try keeping every item in memory.
     */
    def startReadBehind(readBehindId: Long) {
      val file = fileForId(readBehindId)
      if (!file.isDefined) throw new IOException("Unknown id")
      val jf = JournalFile.openWriter(file.get, timer, syncJournal)
      var lastId = -1L
      while (lastId != readBehindId) {
        jf.readNext() match {
          case None => throw new IOException("Can't find id " + head + " in " + file)
          case Some(JournalFile.Record.Put(QueueItem(id, _, _, _))) => lastId = id
          case _ =>
        }
      }
      _readBehind = Some(jf)
      _readBehindId = readBehindId
    }

    /**
     * Read & return the next item in the read-behind journals.
     */
    @tailrec
    final def nextReadBehind(): QueueItem = {
      _readBehind.get.readNext() match {
        case None => {
          _readBehind.foreach { _.close() }
          val file = fileForId(_readBehindId + 1)
          if (!file.isDefined) throw new IOException("Unknown id")
          _readBehind = Some(JournalFile.openWriter(file.get, timer, syncJournal))
          nextReadBehind()
        }
        case Some(JournalFile.Record.Put(item)) => {
          _readBehindId = item.id
          item
        }
        case _ => nextReadBehind()
      }
    }

    /**
     * End read-behind mode, and close any open journal file.
     */
    def endReadBehind() {
      _readBehind.foreach { _.close() }
      _readBehind = None
    }
  }
}


/*

  private def uniqueFile(infix: String, suffix: String = ""): File = {
    var file = new File(queuePath, queueName + infix + Time.now.inMilliseconds + suffix)
    while (!file.createNewFile()) {
      Thread.sleep(1)
      file = new File(queuePath, queueName + infix + Time.now.inMilliseconds + suffix)
    }
    file
  }

  def rotate(reservedItems: Seq[QItem], setCheckpoint: Boolean): Option[Checkpoint] = {
    writer.close()
    val rotatedFile = uniqueFile(".")
    new File(queuePath, queueName).renameTo(rotatedFile)
    size = 0
    calculateArchiveSize()
    open()

    if (readerFilename == Some(queueName)) {
      readerFilename = Some(rotatedFile.getName)
    }

    if (setCheckpoint && !checkpoint.isDefined) {
      checkpoint = Some(Checkpoint(rotatedFile.getName, reservedItems))
    }
    checkpoint
  }

  def erase() {
    try {
      close()
      Journal.archivedFilesForQueue(queuePath, queueName).foreach { filename =>
        new File(queuePath, filename).delete()
      }
      queueFile.delete()
    } catch {
      case _ =>
    }
  }


 */

