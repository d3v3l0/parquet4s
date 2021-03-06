package com.github.mjakubowski84.parquet4s.indefinite

import java.sql.Timestamp

import akka.Done
import akka.kafka.CommitterSettings
import akka.kafka.ConsumerMessage.CommittableOffsetBatch
import akka.kafka.scaladsl.Committer
import akka.stream.FlowShape
import akka.stream.scaladsl.{Flow, Keep, Sink}
import akka.stream.stage.GraphStage
import com.github.mjakubowski84.parquet4s.{ParquetStreams, ParquetWriter}
import com.google.common.io.Files
import org.apache.hadoop.fs.Path
import org.apache.parquet.hadoop.metadata.CompressionCodecName

import scala.concurrent.Future
import scala.concurrent.duration._

object MessageSink {

  case class Data(
                   year: String,
                   month: String,
                   day: String,
                   timestamp: Timestamp,
                   word: String
                 )

  val MaxChunkSize: Int = 128
  val ChunkWriteTimeWindow: FiniteDuration = 10.seconds
  val WriteDirectoryName: String = "messages"

}

trait MessageSink {

  this: Akka =>

  import MessageSink._
  import MessageSource._

  protected val baseWritePath: String = new Path(Files.createTempDir().getAbsolutePath, WriteDirectoryName).toString

  private val writerOptions = ParquetWriter.Options(compressionCodecName = CompressionCodecName.SNAPPY)

  lazy val messageSink: Sink[Message, Future[Done]] =
    Flow[Message]
      .via(saveDataToParquetFlow)
      .map(_.committableOffset)
      .grouped(MaxChunkSize)
      .map(CommittableOffsetBatch.apply)
      .toMat(Committer.sink(CommitterSettings(system)))(Keep.right)

  private lazy val saveDataToParquetFlow: GraphStage[FlowShape[Message, Message]] =
    ParquetStreams
      .viaParquet[Message](baseWritePath)
      .withPreWriteTransformation { message =>
        val timestamp = new Timestamp(message.record.timestamp())
        val localDateTime = timestamp.toLocalDateTime
        Data(
          year = localDateTime.getYear.toString,
          month = localDateTime.getMonthValue.toString,
          day = localDateTime.getDayOfMonth.toString,
          timestamp = timestamp,
          word = message.record.value()
        )
      }
      .withPartitionBy("year", "month", "day")
      .withMaxCount(MaxChunkSize)
      .withMaxDuration(ChunkWriteTimeWindow)
      .withWriteOptions(writerOptions)
      .build()

}
