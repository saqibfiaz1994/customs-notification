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

package uk.gov.hmrc.customs.notification.logging

import com.google.inject.Inject
import javax.inject.Singleton
import uk.gov.hmrc.customs.api.common.logging.CdsLogger
import uk.gov.hmrc.customs.notification.domain.HasId
import uk.gov.hmrc.customs.notification.logging.LoggingHelper2.{format, formatDebug, formatWithHeaders}
import uk.gov.hmrc.customs.notification.model.SeqOfHeader

/*
TODO: Logging framework needs to be refactored so that we pass in an implicit RequestMetaData/LoggingContext object down the call stack rather than SeqOfHeader
RequestMetaData contains all the useful data we wish to log
Current API is forcing us to create an implicit HeaderCarrier just so that we can log
*/
@Singleton
class NotificationLogger2 @Inject()(logger: CdsLogger) {

//  def debug(msg: => String): Unit = {
//    logger.debug(msg)
//  }
  def debug(msg: => String)(implicit rm: HasId): Unit = {
    logger.debug(format(msg, rm))
  }
  def debug(msg: => String, url: => String)(implicit rm: HasId): Unit = {
    logger.debug(formatDebug(msg, Some(url)))
  }
  def debug(msg: => String, url: => String, payload: => String)(implicit rm: HasId): Unit = {
    logger.debug(formatDebug(msg, Some(url), Some(payload)))
  }

  @deprecated //TODO: inline headers processing
  def debugWithHeaders(msg: => String, headers: => SeqOfHeader): Unit = logger.debug(formatWithHeaders(msg, headers))

//  def debug(msg: => String, rm: RequestMetaData): Unit = logger.debug(formatDebug(msg, rm))
  def info(msg: => String)(implicit rm: HasId): Unit = {
    logger.info(format(msg, rm))
  }
  @deprecated //TODO: inline headers processsing
  def errorWithHeaders(msg: => String, headers: => SeqOfHeader): Unit = logger.error(formatWithHeaders(msg, headers))

  def error(msg: => String)(implicit rm: HasId): Unit = {
    logger.error(format(msg, rm))
  }
//  def error(msg: => String, rm: RequestMetaData): Unit = logger.error(formatError(msg, rm))
  def error(msg: => String, t: => Throwable)(implicit rm: HasId): Unit = {
    logger.error(format(msg, rm), t)
  }
  def debugWithoutRequestContext(s: => String): Unit = {
    logger.debug(s)
  }
}

