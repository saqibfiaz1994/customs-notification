package integration

import play.api.libs.json.Json

import akka.stream.scaladsl.Source
import org.reactivestreams.Publisher
import reactivemongo.play.json.JsObjectDocumentWriter
import reactivemongo.api.collections.bson.BSONCollection
import reactivemongo.bson.{BSONDocument, BSONDocumentReader}
import reactivemongo.play.json.collection.JSONCollection
// ReactiveMongo extensions
import reactivemongo.akkastream.{AkkaStreamCursor, State, cursorProducer}

import uk.gov.hmrc.customs.notification.domain.ClientNotification

import scala.concurrent.Future
import scala.concurrent.ExecutionContext.Implicits.global

/**
  * Created by avinder on 10/08/18.
  */
class Example {
//  // ReactiveMongo extensions
//  import reactivemongo.akkastream.{AkkaStreamCursor, State, cursorProducer}

  implicit val system = akka.actor.ActorSystem("reactivemongo-akkastream")
  implicit val materializer = akka.stream.ActorMaterializer.create(system)

  implicit val reader = BSONDocumentReader[Int] { doc =>
    doc.getAsTry[Int]("age").getOrElse(sys.error("Missing age"))
  }

  def foo(collection: BSONCollection): (Source[Int, Future[State]], Publisher[Int]) = {
    val cursor: AkkaStreamCursor[Int] =
      collection.find(BSONDocument.empty /* findAll */).
        sort(BSONDocument("id" -> 1)).cursor[Int]()

    val src: Source[Int, Future[State]] = cursor.documentSource()
    val pub: Publisher[Int] = cursor.documentPublisher()

    src -> pub
  }

  def foo2(collection: JSONCollection): (Source[ClientNotification, Future[State]]) = {
    val cursor: AkkaStreamCursor[ClientNotification] =
      collection.find(Json.obj() /* findAll */).
        sort(Json.obj("id" -> 1)).cursor[ClientNotification]()

    val src: Source[ClientNotification, Future[State]] = cursor.documentSource()

    src
  }
}
