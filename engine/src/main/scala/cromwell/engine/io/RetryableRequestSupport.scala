package cromwell.engine.io

import java.io.IOException
import java.net.{SocketException, SocketTimeoutException}
import com.google.cloud.storage.StorageException
import cromwell.engine.io.gcs.GcsBatchFlow.BatchFailedException
import cromwell.engine.io.nio.ChecksumFailedException

import javax.net.ssl.SSLException

object RetryableRequestSupport {

  /**
    * Failures that are considered retryable. Retrying them increases the retry counter.
    * The default count is `5` and may be customized with `system.io.number-of-attempts`.
    */
  def isRetryable(failure: Throwable): Boolean = failure match {
    case gcs: StorageException =>
      gcs.isRetryable ||
      isRetryable(gcs.getCause) ||
      AdditionalRetryableHttpCodes.contains(gcs.getCode) ||
      Option(gcs.getMessage).exists(msg => AdditionalRetryableErrorMessages.contains(msg.toLowerCase))
    case _: SSLException => true
    case _: BatchFailedException => true
    case _: ChecksumFailedException => true
    case _: SocketException => true
    case _: SocketTimeoutException => true
    case ioE: IOException if Option(ioE.getMessage).exists(_.contains("Error getting access token for service account")) => true
    case ioE: IOException => isGcs500(ioE) || isGcs503(ioE) || isGcs504(ioE) || isAws504(ioE)
    case other =>
      // Infinitely retryable is a subset of retryable
      isInfinitelyRetryable(other)
  }

  private val AdditionalRetryableHttpCodes = List(
    // HTTP 410: Gone
    // From Google doc (https://cloud.google.com/storage/docs/json_api/v1/status-codes):
    // "You have attempted to use a resumable upload session that is no longer available.
    // If the reported status code was not successful and you still wish to upload the file, you must start a new session."
    410,
    // Some 503 errors seem to yield "false" on the "isRetryable" method because they are not retried.
    // The CloudStorage exception mechanism is not flawless yet (https://github.com/GoogleCloudPlatform/google-cloud-java/issues/1545)
    // so that could be the cause.
    // For now explicitly lists 503 as a retryable code here to work around that.
    503
  )

  // Error messages not included in the list of built-in GCS retryable errors (com.google.cloud.storage.StorageException) but that we still want to retry
  private val AdditionalRetryableErrorMessages = List(
    "Connection closed prematurely",
    // This is a 400 "non-retryable" error that is nevertheless sporadic and succeeds on subsequent re-runs with identical parameters [BW-1320]
    "User project specified in the request is invalid"
  ).map(_.toLowerCase)

  /**
    * ATTENTION: Transient failures are retried *forever*
    * Be careful when adding error codes to this method.
    */
  def isInfinitelyRetryable(failure: Throwable): Boolean = {

    // Retry forever because eventually the user will have quota capacity again
    // https://cloud.google.com/storage/docs/json_api/v1/status-codes#429_Too_Many_Requests
    def isGcsRateLimitException(failure: Throwable): Boolean = failure match {
      case gcs: StorageException => gcs.getCode == 429
      case _ => false
    }

    isGcsRateLimitException(failure)
  }

  def isGcs500(failure: Throwable): Boolean =
    Option(failure.getMessage).exists(msg =>
      msg.contains("Could not read from gs") &&
        msg.contains("500 Internal Server Error")
    )

  def isGcs503(failure: Throwable): Boolean =
    Option(failure.getMessage).exists(msg =>
      msg.contains("Could not read from gs") &&
        msg.contains("503 Service Unavailable")
    )

  def isGcs504(failure: Throwable): Boolean =
    Option(failure.getMessage).exists(msg =>
      msg.contains("Could not read from gs") &&
        msg.contains("504 Gateway Timeout")
    )
  
  // AWS timeout error
  def isAws504(failure: Throwable): Boolean = {
    Option(failure.getMessage).exists(msg =>
      ( 
        // timeout in reading form s3. 
        msg.contains("Could not read from s3") && 
        msg.contains("Timeout waiting for connection") 
      ) || (
        // reading in cromwell wdl (read_lines() etc)
        msg.contains("Failed to evaluate") &&
        msg.contains("s3://") && 
        msg.contains("Timed out after")
      )
    )
  }
  // General AWS IO error : all items unreadable except rc.txt files (might be missing)
  //   => mainly for testing. will retry mis-specified s3 paths as well... 
  def isAwsIO(failure:Throwable): Boolean = {
    Option(failure.getMessage).exists(msg =>
      msg.contains("Could not read from s3") && 
      ! msg.contains("-rc.txt") 
    )
  }

}
