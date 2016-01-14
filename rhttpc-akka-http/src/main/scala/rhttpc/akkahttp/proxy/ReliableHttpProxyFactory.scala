/*
 * Copyright 2015 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package rhttpc.akkahttp.proxy

import akka.actor._
import akka.http.scaladsl.Http
import akka.http.scaladsl.model.{HttpEntity, HttpRequest, HttpResponse}
import akka.stream.Materializer
import akka.stream.scaladsl.{Flow, Sink, Source}
import org.slf4j.LoggerFactory
import rhttpc.client.protocol.Correlated
import rhttpc.client.proxy._

import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future}
import scala.language.postfixOps
import scala.util.{Failure, Success, Try}

object ReliableHttpProxyFactory {

  private lazy val log = LoggerFactory.getLogger(getClass)

  def send(successRecognizer: SuccessHttpResponseRecognizer, batchSize: Int)
          (corr: Correlated[HttpRequest])
          (implicit actorSystem: ActorSystem, materialize: Materializer): Future[Try[HttpResponse]] = {
    import actorSystem.dispatcher
    send(prepareHttpFlow(batchSize), successRecognizer)(corr)
  }

  private def prepareHttpFlow(batchSize: Int)
                             (implicit actorSystem: ActorSystem, materialize: Materializer) = {
    import actorSystem.dispatcher
    Http().superPool[String]().mapAsync(batchSize) {
      case (tryResponse, id) =>
        tryResponse match {
          case Success(response) =>
            response.toStrict(1 minute).map(strict => (Success(strict), id))
          case failure =>
            Future.successful((failure, id))
        }
    }
  }

  private def send(httpFlow: Flow[(HttpRequest, String), (Try[HttpResponse], String), Any], successRecognizer: SuccessHttpResponseRecognizer)
                  (corr: Correlated[HttpRequest])
                  (implicit ec: ExecutionContext, materialize: Materializer): Future[Try[HttpResponse]] = {
    import collection.convert.wrapAsScala._
    log.debug(
      s"""Sending request for ${corr.correlationId} to ${corr.msg.getUri()}. Headers:
          |${corr.msg.getHeaders().map(h => "  " + h.name() + ": " + h.value()).mkString("\n")}
          |Body:
          |${corr.msg.entity.asInstanceOf[HttpEntity.Strict].data.utf8String}""".stripMargin
    )
    val logResp = logResponse(corr) _
    Source.single((corr.msg, corr.correlationId)).via(httpFlow).runWith(Sink.head).map {
      case (tryResponse, correlationId) =>
        tryResponse match {
          case success@Success(response) if successRecognizer.isSuccess(response) =>
            logResp(response, "success response")
            success
          case Success(response) =>
            logResp(response, "response recognized as non-success")
            Failure(NonSuccessResponse)
          case failure@Failure(ex) =>
            log.error(s"Got failure for ${corr.correlationId} to ${corr.msg.getUri()}", ex)
            failure
        }
    }
  }

  private def logResponse(corr: Correlated[HttpRequest])
                         (response: HttpResponse, additionalInfo: String) = {
    import collection.convert.wrapAsScala._
    log.debug(
      s"""Got $additionalInfo for ${corr.correlationId} to ${corr.msg.getUri()}. Status: ${response.status.value}. Headers:
          |${response.getHeaders().map(h => "  " + h.name() + ": " + h.value()).mkString("\n")}
          |Body:
          |${response.entity.asInstanceOf[HttpEntity.Strict].data.utf8String}""".stripMargin
    )
  }

}