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
import uk.gov.hmrc.customs.notification.logging.LoggingHelper.{formatDebug, formatError, formatInfo}
import uk.gov.hmrc.customs.notification.model.SeqOfHeader
import uk.gov.hmrc.http.HeaderCarrier

/*
TODO: Logging framework needs to be refactored so that we pass in an implicit RequestMetaData/LoggingContext object down the call stack rather than SeqOfHeader
RequestMetaData contains all the useful data we wish to log
Current API is forcing us to create an implicit HeaderCarrier just so that we can log
*/
@Singleton
class NotificationLogger @Inject()(logger: CdsLogger) {

/*
TODO: recalculate usages
check logging in connectors
check references to NotificationLogger (8 and mainly connectors) and remove
TODO: convert errorWithHeaders to accept Header rather than Seq[(String, String)]
create StubNotificationLogger2
inline funky stuff
check references for HeaderCarrier
more tests for new logger
plug testing gaps
raise PR
*/

  //DONE
  // 1 usages only in NotificationLoggerSpec
  def debug(msg: => String, url: => String)(implicit hc: HeaderCarrier): Unit = logger.debug(formatDebug(msg, Some(url), None))
  // 1 usages only in NotificationLoggerSpec
  def debug(msg: => String, url: => String, payload: => String)(implicit hc: HeaderCarrier): Unit = logger.debug(formatDebug(msg, Some(url), Some(payload)))
  // 1 usages only in NotificationLoggerSpec.scala
  def error(msg: => String, headers: => SeqOfHeader): Unit = logger.error(formatError(msg, headers))
  // 1 usages in Tests
  def debugWithoutRequestContext(s: => String): Unit = logger.debug(s)
  // 1 only in Test
  def error(msg: => String)(implicit hc: HeaderCarrier): Unit = logger.error(formatError(msg))
  // 1 in test
  def error(msg: => String, t: => Throwable)(implicit hc: HeaderCarrier): Unit = logger.error(formatError(msg), t)
  // 1 in test
  def debug(msg: => String, headers: => SeqOfHeader): Unit = logger.debug(formatDebug(msg, headers))
  // 1 usages only in tests
  def debug(msg: => String)(implicit hc: HeaderCarrier): Unit = logger.debug(formatDebug(msg, None, None))
  // 1 usages FailedPushEmailPollingService.scala
  def info(msg: => String)(implicit hc: HeaderCarrier): Unit = logger.info(formatInfo(msg))


}

