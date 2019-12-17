/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.spark.sql.aliyun.logservice

import java.util
import java.util.concurrent.{Executors, ThreadFactory}

import scala.collection.JavaConverters._
import scala.collection.mutable
import scala.concurrent.{ExecutionContext, Future}
import scala.concurrent.duration.Duration
import scala.util.control.NonFatal

import com.aliyun.openservices.aliyun.log.producer.{LogProducer, ProducerConfig, ProjectConfig}
import com.aliyun.openservices.log.common.Consts.CursorMode
import com.aliyun.openservices.log.common.Histogram
import org.apache.commons.cli.MissingArgumentException

import org.apache.spark.internal.Logging
import org.apache.spark.streaming.aliyun.logservice.LoghubClientAgent
import org.apache.spark.util.{ThreadUtils, UninterruptibleThread}

class LoghubOffsetReader(readerOptions: Map[String, String]) extends Logging {
  val loghubReaderThread = Executors.newSingleThreadExecutor(new ThreadFactory {
    override def newThread(r: Runnable): Thread = {
      val t = new UninterruptibleThread("Loghub Offset Reader") {
        override def run(): Unit = {
          r.run()
        }
      }
      t.setDaemon(true)
      t
    }
  })
  val execContext = ExecutionContext.fromExecutorService(loghubReaderThread)
  private def runUninterruptibly[T](body: => T): T = {
    if (!Thread.currentThread.isInstanceOf[UninterruptibleThread]) {
      val future = Future {
        body
      }(execContext)
      ThreadUtils.awaitResult(future, Duration.Inf)
    } else {
      body
    }
  }
  private var latestHistograms: Array[Histogram] = null

  private val logProject = readerOptions.getOrElse("sls.project",
    throw new MissingArgumentException("Missing logService project (='sls.project')."))
  private val logStore = readerOptions.getOrElse("sls.store",
    throw new MissingArgumentException("Missing logService store (='sls.store')."))
  private val maxOffsetFetchAttempts = readerOptions.getOrElse("fetchOffset.numRetries", "3").toInt
  private val offsetFetchAttemptIntervalMs =
    readerOptions.getOrElse("fetchOffset.retryIntervalMs", "1000").toLong

  var logServiceClient: LoghubClientAgent =
    LoghubOffsetReader.getOrCreateLoghubClient(readerOptions)

  private def withRetriesWithoutInterrupt[T](body: => T): T = {
    assert(Thread.currentThread().isInstanceOf[UninterruptibleThread])
    synchronized {
      var result: Option[T] = None
      var attempt = 1
      var lastException: Throwable = null
      while (result.isEmpty && attempt <= maxOffsetFetchAttempts
        && !Thread.currentThread().isInterrupted) {
        Thread.currentThread match {
          case ut: UninterruptibleThread =>
            ut.runUninterruptibly {
              try {
                result = Some(body)
              } catch {
                case NonFatal(e) =>
                  lastException = e
                  logWarning(s"Error in attempt $attempt getting loghub offsets: ", e)
                  attempt += 1
                  Thread.sleep(offsetFetchAttemptIntervalMs)
              }
            }
          case _ =>
            throw new IllegalStateException(
              "Loghub client APIs must be executed on a o.a.spark.util.UninterruptibleThread")
        }
      }
      if (Thread.interrupted()) {
        throw new InterruptedException()
      }
      if (result.isEmpty) {
        assert(attempt > maxOffsetFetchAttempts)
        assert(lastException != null)
        throw lastException
      }
      result.get
    }
  }

  def fetchLoghubShard(): Set[LoghubShard] = {
    logServiceClient.ListShard(logProject, logStore).GetShards().asScala
      .map(shard => LoghubShard(logProject, logStore, shard.GetShardId())).toSet
  }

  def fetchEarliestOffsets(loghubShards: Set[LoghubShard]): Map[LoghubShard, (Int, String)] =
    runUninterruptibly {
      withRetriesWithoutInterrupt {
        loghubShards.map(shard => {
          val cursor =
            logServiceClient.GetCursor(logProject, logStore, shard.shard, CursorMode.BEGIN)
          val cursorTime =
            logServiceClient.GetCursorTime(logProject, logStore, shard.shard, cursor.GetCursor())
          (shard, (cursorTime.GetCursorTime(), cursor.GetCursor()))
        }).toMap
      }
    }

  def fetchEarliestOffsets(): Map[LoghubShard, (Int, String)] = runUninterruptibly {
    withRetriesWithoutInterrupt {
      fetchLoghubShard().map {case loghubShard =>
        val cursor =
          logServiceClient.GetCursor(logProject, logStore, loghubShard.shard, CursorMode.BEGIN)
        val cursorTime =
          logServiceClient.GetCursorTime(logProject, logStore, loghubShard.shard,
            cursor.GetCursor())
        (loghubShard, (cursorTime.GetCursorTime(), cursor.GetCursor()))
      }.toMap
    }
  }

  def fetchLatestOffsets(): Map[LoghubShard, (Int, String)] = runUninterruptibly {
    withRetriesWithoutInterrupt {
      fetchLoghubShard().map { case loghubShard =>
        val cursor =
          logServiceClient.GetCursor(logProject, logStore, loghubShard.shard, CursorMode.END)
        val cursorTime =
          logServiceClient.GetCursorTime(logProject, logStore, loghubShard.shard,
            cursor.GetCursor())
        (loghubShard, (cursorTime.GetCursorTime(), cursor.GetCursor()))
      }.toMap
    }
  }

  private def getLatestHistograms(startOffset: Int): Array[Histogram] = {
    val lag = System.currentTimeMillis() / 1000 - startOffset

    val maxRange = 60 * 5
    val minRange = 60
    val ranges: Array[(Int, Int)] = if (lag > maxRange) {
      val numRanges = math.min(lag, 3600 * 6) / maxRange
      Array.tabulate(numRanges.toInt)(idx => {
        (startOffset + (idx - 1) * maxRange, startOffset + idx * maxRange)
      })
    } else if (lag > minRange) {
      val numRanges = math.min(lag, 300) / minRange
      Array.tabulate(numRanges.toInt)(idx => {
        (startOffset + (idx - 1) * minRange, startOffset + idx * minRange)
      })
    } else {
      throw new Exception("Should not be called here.")
    }

    val latestHistograms = ranges.map { case (startOffset, endOffset) =>
      getRangeHistograms(startOffset, endOffset).asScala.toArray
    }.reduce(_ ++ _)

    latestHistograms.map(_.mFromTime).reduceLeft((l, r) => {
      if (l >= r) {
        throw new Exception("Histograms should be ordered by time in second.")
      }
      r
    })

    latestHistograms
  }

  private def getRangeHistograms(startOffset: Int, endOffset: Int): util.ArrayList[Histogram] = {
    var tries = 10
    var result = logServiceClient
      .GetHistograms(logProject, logStore, startOffset, endOffset, "", "*")
    while (!result.IsCompleted() && tries > 0) {
      result = logServiceClient
        .GetHistograms(logProject, logStore, startOffset, endOffset, "", "*")
      tries -= 1
    }
    if (!result.IsCompleted()) {
      logWarning(s"The result of histograms of $logProject/$logStore between " +
        s"$startOffset and $endOffset is not completed. So it may not be accurate to " +
        s"calculate offset range.")
    }
    result.GetHistograms()
  }

  def rateLimit(startOffset: Int, maxOffsetsPerTrigger: Option[Long]): Int = {
    runUninterruptibly {
      withRetriesWithoutInterrupt {
        val lag = System.currentTimeMillis() / 1000 - startOffset
        if (lag <= 60) {
          val minCursorTime = fetchLatestOffsets().values.map(_._1).min
          require(minCursorTime >= startOffset, s"endCursorTime[$minCursorTime] should " +
            s"not be less than startCursorTime[$startOffset].")
          return minCursorTime
        }

        if (latestHistograms == null) {
          latestHistograms = getLatestHistograms(startOffset)
        }

        val maxOffset = latestHistograms.map(_.mToTime).max
        if (startOffset >= maxOffset) {
          latestHistograms = getLatestHistograms(startOffset)
        }

        var endCursorTime = startOffset
        var count = 0L
        latestHistograms.filter(_.mFromTime >= startOffset)
          .foreach(e => {
            if (count < maxOffsetsPerTrigger.get) {
              endCursorTime = e.mToTime
              count += e.mCount
            }
          })

        require(endCursorTime >= startOffset, s"endCursorTime[$endCursorTime] should not " +
          s"be less than startCursorTime[$startOffset].")
        endCursorTime
      }
    }
  }

  def close(): Unit = {
    logServiceClient = null
    loghubReaderThread.shutdown()
  }
}

object LoghubOffsetReader extends Logging with Serializable {
  private val lock = new Object
  @transient private var logProducer: LogProducer = null
  @transient private[logservice] var logServiceClientPool =
    new mutable.HashMap[(String, String), LoghubClientAgent]()

  def getOrCreateLoghubClient(
      accessKeyId: String,
      accessKeySecret: String,
      endpoint: String): LoghubClientAgent = {
    if (!logServiceClientPool.contains((accessKeyId, endpoint))) {
      val logServiceClient = new LoghubClientAgent(endpoint, accessKeyId, accessKeySecret)
      logServiceClientPool.put((accessKeyId, endpoint), logServiceClient)
    }
    logServiceClientPool((accessKeyId, endpoint))
  }

  def getOrCreateLoghubClient(sourceOptions: Map[String, String]): LoghubClientAgent = {
    val accessKeyId = sourceOptions.getOrElse("access.key.id",
      throw new MissingArgumentException("Missing access key id (='access.key.id')."))
    val accessKeySecret = sourceOptions.getOrElse("access.key.secret",
      throw new MissingArgumentException("Missing access key secret (='access.key.secret')."))
    val endpoint = sourceOptions.getOrElse("endpoint",
      throw new MissingArgumentException("Missing log store endpoint (='endpoint')."))
    getOrCreateLoghubClient(accessKeyId, accessKeySecret, endpoint)
  }

  def getOrCreateLogProducer(sourceOptions: Map[String, String]): LogProducer = {
    if (logProducer == null) {
      val logProject = sourceOptions.getOrElse("sls.project",
        throw new MissingArgumentException("Missing logService project (='sls.project')."))
      val accessKeyId = sourceOptions.getOrElse("access.key.id",
        throw new MissingArgumentException("Missing access key id (='access.key.id')."))
      val accessKeySecret = sourceOptions.getOrElse("access.key.secret",
        throw new MissingArgumentException("Missing access key secret (='access.key.secret')."))
      val endpoint = sourceOptions.getOrElse("endpoint",
        throw new MissingArgumentException("Missing log store endpoint (='endpoint')."))
      val config = new ProducerConfig()
      logProducer = new LogProducer(config)
      logProducer.putProjectConfig(
        new ProjectConfig(logProject, endpoint, accessKeyId, accessKeySecret))
    }
    logProducer
  }

  // only for test
  private[logservice] def setLogServiceClient(
      accessKeyId: String,
      endpoint: String,
      testClient: LoghubClientAgent): Unit = {
    lock.synchronized {
      logServiceClientPool.put((accessKeyId, endpoint), testClient)
    }
  }

  // only for test
  private[logservice] def resetLogServiceClient(
      accessKeyId: String,
      endpoint: String): Unit = {
    lock.synchronized {
      logServiceClientPool.remove((accessKeyId, endpoint))
    }
  }

  // only for test
  private[logservice] def resetClientPool(): Unit = {
    lock.synchronized {
      logServiceClientPool.clear()
    }
  }
}
