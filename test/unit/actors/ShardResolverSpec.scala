package unit.actors

import uk.gov.hmrc.customs.notification.actors.NotificationsActor
import uk.gov.hmrc.customs.notification.actors.model.NotificationCmd
import uk.gov.hmrc.customs.notification.model.ClientId
import uk.gov.hmrc.play.test.UnitSpec

class ShardResolverSpec extends UnitSpec {
  "shardResolver" should {
    "client id = 1000" in {
      NotificationsActor.shardResolver(msg("1000")) shouldBe "23"
      NotificationsActor.shardResolver(msg("1000")) shouldBe "23"
    }
    "client id = 2000" in {
      NotificationsActor.shardResolver(msg("2000")) shouldBe "14"
      NotificationsActor.shardResolver(msg("2000")) shouldBe "14"
    }
  }

  "idExtractor" should {
    "client id = 1000" in {
      val msg1000 = msg("1000")
      NotificationsActor.idExtractor(msg1000) shouldBe ("1000", msg1000)
    }
  }

  private def msg(cid: ClientId) = new NotificationCmd {
    override val clientId: ClientId = cid
  }
}
