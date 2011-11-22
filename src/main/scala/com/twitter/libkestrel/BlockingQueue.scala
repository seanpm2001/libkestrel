/*
 * Copyright 2011 Twitter, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License. You may obtain
 * a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.twitter.libkestrel

import com.twitter.util.{Future, Time}

trait BlockingQueue[A <: AnyRef] {
  def put(item: A): Boolean
  def putHead(item: A)
  def size: Int
  def get(): Future[Option[A]]
  def get(deadline: Time): Future[Option[A]]
  def poll(): Option[A]
  def pollIf(predicate: A => Boolean): Option[A]
  def toDebug: String
  def close()
}