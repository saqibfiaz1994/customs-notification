/*
 * Copyright 2018 HM Revenue & Customs
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

package uk.gov.hmrc.customs.notification.controllers

import java.util.UUID
import javax.inject.{Inject, Singleton}

import play.api.mvc._
import uk.gov.hmrc.customs.api.common.controllers.ErrorResponse
import uk.gov.hmrc.customs.notification.connectors.ApiSubscriptionFieldsConnector
import uk.gov.hmrc.customs.notification.controllers.CustomErrorResponses.ErrorCdsClientIdNotFound
import uk.gov.hmrc.customs.notification.controllers.CustomHeaderNames._
import uk.gov.hmrc.customs.notification.logging.NotificationLogger
import uk.gov.hmrc.customs.notification.services.CustomsNotificationServiceForSpike
import uk.gov.hmrc.customs.notification.services.config.ConfigService
import uk.gov.hmrc.http.HeaderCarrier
import uk.gov.hmrc.play.bootstrap.controller.BaseController

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scala.xml.NodeSeq


@Singleton
class CustomsNotificationControllerForSpike @Inject()(logger: NotificationLogger,
                                                      customsNotificationService: CustomsNotificationServiceForSpike,
                                                      callbackDetailsConnector: ApiSubscriptionFieldsConnector,
                                                      configService: ConfigService)
  extends BaseController with HeaderValidator {

  override val notificationLogger: NotificationLogger = logger
  private lazy val maybeBasicAuthToken: Option[String] = configService.maybeBasicAuthToken
  private lazy val xmlValidationErrorMessage = "Request body does not contain well-formed XML."

  def submit(): Action[AnyContent] = validateHeaders(maybeBasicAuthToken) async {
    implicit request =>
      request.body.asXml match {
        case Some(xml) => process(xml, requestMetaData(request.headers))
        case None =>
          notificationLogger.error(xmlValidationErrorMessage)
          Future.successful(ErrorResponse.errorBadRequest(xmlValidationErrorMessage).XmlResult)
      }
  }

  private def requestMetaData(headers: Headers): RequestMetaData = {
    // headers have been validated so safe to do a naked get except badgeId which is optional
    RequestMetaData(headers.get(X_CDS_CLIENT_ID_HEADER_NAME).get,
      UUID.fromString(headers.get(X_CONVERSATION_ID_HEADER_NAME).get),
      headers.get(X_BADGE_ID_HEADER_NAME))
  }

  private def process(xml: NodeSeq, md: RequestMetaData)(implicit hc: HeaderCarrier): Future[Result] = {
    logger.debug(s"Received notification with payload: $xml, metaData: $md")

    (for {
      cd <- callbackDetailsConnector.getClientData(md.clientId)
      clientId <- callbackDetailsConnector.getClientId(md.clientId)
    } yield (cd, clientId)).flatMap{
      case (Some(callbackData), Some(clientId)) =>
        customsNotificationService.handleNotification(clientId, xml, callbackData, md).map{ ack: Any =>
          logger.info(s"Notification accepted to be delivered with acknowledgement=$ack")
          Results.Accepted(ack.toString)
        }
      case _ =>
        logger.error("Declarant data not found")
        Future.successful(ErrorCdsClientIdNotFound.XmlResult)
    }.recover {
      case ex: Throwable =>
        notificationLogger.error("Failed to enqueue notification for sending" + ex.getMessage)
        ErrorResponse.errorInternalServerError("Internal Server Error").XmlResult
    }
  }

}
