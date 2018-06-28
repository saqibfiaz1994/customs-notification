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

package unit.services

import java.util.UUID

import org.joda.time.DateTime
import uk.gov.hmrc.customs.notification.domain._

object ClientWorkerTestData {
  val ClientSubscriptionIdAsString = "eaca01f9-ec3b-4ede-b263-61b626dde232"
  val TestClientSubscriptionId = ClientSubscriptionId(UUID.fromString(ClientSubscriptionIdAsString))
  val Headers = Seq("h1" -> "v1")
  val Payload = "PAYLOAD"
  val ContentType = "CONTENT_TYPE"
  val notification1 = Notification(Headers, Payload, ContentType)
  val TimeStampOne = DateTime.now
  val ClientNotification1 = ClientNotification(TestClientSubscriptionId, notification1, TimeStampOne)
  val DeclarantCallbackDataOne = DeclarantCallbackData("URL", "SECURITY_TOKEN")
}



