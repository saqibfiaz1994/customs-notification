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

package uk.gov.hmrc.customs.notification.actors

import uk.gov.hmrc.customs.notification.domain.PublicNotificationRequest

import scala.concurrent.Future

//TODO: wire in non actor logging
trait DummyConnector {
  var sendCount: Int

  // fails on 1st call, successful on 2nd, fails on 3rd, ... etc etc
  def dummyPost(notification: PublicNotificationRequest): Future[Unit] = {
    if (sendCount % 2 == 0) {
      println(s"dummyPostUsingSomeAsyncHttpClientApi - successful post sendCount=$sendCount")
      Future.successful(())
    } else {
      println(s"dummyPostUsingSomeAsyncHttpClientApi - failed post. sendCount=$sendCount")
      //Future.failed(new IllegalStateException("Some really shitty HTTP client exception"))
      throw new IllegalStateException(s"Some really shitty HTTP client exception sendCount=$sendCount")
    }
  }
}
