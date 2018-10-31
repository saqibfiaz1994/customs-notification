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

package uk.gov.hmrc.customs.notification.services

import akka.stream.scaladsl.Source
import com.google.inject.Singleton
import play.api.libs.json.{JsObject, JsValue, Json}
import reactivemongo.play.json.JsObjectDocumentWriter
import reactivemongo.play.json.collection.JSONCollection
// ReactiveMongo extensions
import reactivemongo.akkastream.{AkkaStreamCursor, State, cursorProducer}
import uk.gov.hmrc.customs.notification.domain.ClientNotification

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

@Singleton
class Streamer {

  private implicit val system = akka.actor.ActorSystem("reactivemongo-akkastream")
  private implicit val materializer = akka.stream.ActorMaterializer.create(system)

  def stream(collection: JSONCollection, selector: JsObject = Json.obj()): (Source[ClientNotification, Future[State]]) = {
    val cursor: AkkaStreamCursor[ClientNotification] =
      collection.find(selector)./*sort(Json.obj("id" -> 1)).*/cursor[ClientNotification]()

    val src: Source[ClientNotification, Future[State]] = cursor.documentSource()

    src
  }
}
