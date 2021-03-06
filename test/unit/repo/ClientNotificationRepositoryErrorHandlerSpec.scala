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

package unit.repo

import org.scalatest.mockito.MockitoSugar
import reactivemongo.api.commands.{DefaultWriteResult, WriteConcernError, WriteError}
import uk.gov.hmrc.customs.notification.domain.ClientNotification
import uk.gov.hmrc.customs.notification.repo.ClientNotificationRepositoryErrorHandler
import uk.gov.hmrc.play.test.UnitSpec
import unit.logging.StubCdsLogger

class ClientNotificationRepositoryErrorHandlerSpec extends UnitSpec with MockitoSugar {

  private val stubCdsLogger = StubCdsLogger()
  private val errorHandler = new ClientNotificationRepositoryErrorHandler(stubCdsLogger)
  private val notification = mock[ClientNotification]

  "NotificationRepositoryErrorHandler" can {

    "handle save" should {

      "return notification if there are no database errors and at least one record inserted" in {
        val successfulWriteResult = writeResult(alteredRecords = 1)

        errorHandler.handleSaveError(successfulWriteResult, "ERROR_MSG", notification) shouldBe true
      }

      "throw a RuntimeException if there are no database errors but no record inserted" in {
        val noRecordsWriteResult = writeResult(alteredRecords = 0)

        val caught = intercept[RuntimeException](errorHandler.handleSaveError(noRecordsWriteResult, "ERROR_MSG", notification))

        caught.getMessage shouldBe "ERROR_MSG"
      }

      "throw a RuntimeException if there is a database error" in {
        val writeConcernError = Some(WriteConcernError(1, "ERROR"))
        val errorWriteResult = writeResult(alteredRecords = 0, writeConcernError = writeConcernError)

        val caught = intercept[RuntimeException](errorHandler.handleSaveError(errorWriteResult, "ERROR_MSG", notification))

        caught.getMessage shouldBe "ERROR_MSG. WriteConcernError(1,ERROR)"
      }

    }

    "handle Delete" should {
      "return true if there are no database errors and at least one record deleted" in {
        val successfulWriteResult = writeResult(alteredRecords = 1)

        errorHandler.handleDeleteError(successfulWriteResult, "ERROR_MSG") shouldBe true
      }

      "return false if there are no database errors and no record deleted" in {
        val noDeletedRecordsWriteResult = writeResult(alteredRecords = 0)

        errorHandler.handleDeleteError(noDeletedRecordsWriteResult, "ERROR_MSG") shouldBe false
      }

      "throw a RuntimeException if there is a database error" in {
        val writeConcernError = Some(WriteConcernError(1, "ERROR"))
        val errorWriteResult = writeResult(alteredRecords = 0, writeConcernError = writeConcernError)

        val caught = intercept[RuntimeException](errorHandler.handleDeleteError(errorWriteResult, "ERROR_MSG"))

        caught.getMessage shouldBe "ERROR_MSG. WriteConcernError(1,ERROR)"
      }
    }
  }

  private def writeResult(alteredRecords: Int, writeErrors: Seq[WriteError] = Nil,
                          writeConcernError: Option[WriteConcernError] = None) = {
    DefaultWriteResult(
      ok = true,
      n = alteredRecords,
      writeErrors = writeErrors,
      writeConcernError = writeConcernError,
      code = None,
      errmsg = None)
  }

}
