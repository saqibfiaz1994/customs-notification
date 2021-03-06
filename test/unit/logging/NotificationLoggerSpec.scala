/*
 * Copyright 2019 HM Revenue & Customs
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

package unit.logging

import org.mockito.ArgumentMatchers.any
import org.scalatest.mockito.MockitoSugar
import uk.gov.hmrc.customs.api.common.logging.CdsLogger
import uk.gov.hmrc.customs.notification.logging.NotificationLogger
import uk.gov.hmrc.play.test.UnitSpec
import util.MockitoPassByNameHelper.PassByNameVerifier
import util.TestData.requestMetaData

class NotificationLoggerSpec extends UnitSpec with MockitoSugar {

  trait SetUp {
    val mockCdsLogger: CdsLogger = mock[CdsLogger]
    val logger = new NotificationLogger(mockCdsLogger)
  }

  private implicit val implicitRequestMetaData = requestMetaData

  "DeclarationsLogger" should {
    "debug(s: => String)" in new SetUp {
      logger.debug("msg")

      PassByNameVerifier(mockCdsLogger, "debug")
        .withByNameParam("[conversationId=eaca01f9-ec3b-4ede-b263-61b626dde231][fieldsId=eaca01f9-ec3b-4ede-b263-61b626dde232][badgeId=ABCDEF1234][eoriIdentifier=IAMEORI][correlationId=CORRID2234] msg")
        .verify()
    }
    "debug(s: => String, url: => String)" in new SetUp {
      logger.debug("msg", "url")

      PassByNameVerifier(mockCdsLogger, "debug")
        .withByNameParam("[conversationId=eaca01f9-ec3b-4ede-b263-61b626dde231][fieldsId=eaca01f9-ec3b-4ede-b263-61b626dde232][badgeId=ABCDEF1234][eoriIdentifier=IAMEORI][correlationId=CORRID2234] msg url=url\n")
        .verify()
    }
    "debug(s: => String, url: => String, payload: => String)" in new SetUp {
      logger.debug("msg", "url", "payload")

      PassByNameVerifier(mockCdsLogger, "debug")
        .withByNameParam("[conversationId=eaca01f9-ec3b-4ede-b263-61b626dde231][fieldsId=eaca01f9-ec3b-4ede-b263-61b626dde232][badgeId=ABCDEF1234][eoriIdentifier=IAMEORI][correlationId=CORRID2234] msg url=url\n\npayload=\npayload")
        .verify()
    }
    "info(s: => String)" in new SetUp {
      logger.info("msg")

      PassByNameVerifier(mockCdsLogger, "info")
        .withByNameParam("[conversationId=eaca01f9-ec3b-4ede-b263-61b626dde231][fieldsId=eaca01f9-ec3b-4ede-b263-61b626dde232][badgeId=ABCDEF1234][eoriIdentifier=IAMEORI][correlationId=CORRID2234] msg")
        .verify()
    }
    "error(s: => String)" in new SetUp {
      logger.error("msg")

      PassByNameVerifier(mockCdsLogger, "error")
        .withByNameParam("[conversationId=eaca01f9-ec3b-4ede-b263-61b626dde231][fieldsId=eaca01f9-ec3b-4ede-b263-61b626dde232][badgeId=ABCDEF1234][eoriIdentifier=IAMEORI][correlationId=CORRID2234] msg")
        .verify()
    }
    "error(s: => String, t: => Throwable)" in new SetUp {
      logger.error("msg", new Exception("message"))

      PassByNameVerifier(mockCdsLogger, "error")
        .withByNameParam("[conversationId=eaca01f9-ec3b-4ede-b263-61b626dde231][fieldsId=eaca01f9-ec3b-4ede-b263-61b626dde232][badgeId=ABCDEF1234][eoriIdentifier=IAMEORI][correlationId=CORRID2234] msg")
        .withByNameParamMatcher[Throwable](any[Throwable])
        .verify()
    }
  }
}
