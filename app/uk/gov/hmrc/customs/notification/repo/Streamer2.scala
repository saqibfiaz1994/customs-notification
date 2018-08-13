///*
// * Copyright 2018 HM Revenue & Customs
// *
// * Licensed under the Apache License, Version 2.0 (the "License");
// * you may not use this file except in compliance with the License.
// * You may obtain a copy of the License at
// *
// *     http://www.apache.org/licenses/LICENSE-2.0
// *
// * Unless required by applicable law or agreed to in writing, software
// * distributed under the License is distributed on an "AS IS" BASIS,
// * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// * See the License for the specific language governing permissions and
// * limitations under the License.
// */
//
//package uk.gov.hmrc.customs.notification.repo
//
//// ReactiveMongo extensions
//import _root_.reactivemongo.akkastream.{AkkaStreamCursor, State, cursorProducer}
//
//@Singleton
//class Streamer2 {
//
////  def stream(collection: JSONCollection, selector: JsObject = Json.obj()): (Source[ClientNotification, Future[reactivemongo.akkastream.State]]) = {
////    val cursor: AkkaStreamCursor[ClientNotification] =
////      collection.find(selector)./*sort(Json.obj("id" -> 1)).*/cursor[ClientNotification]()
////
////    val src: Source[ClientNotification, Future[State]] = cursor.documentSource()
////
////    src
////  }
//}
