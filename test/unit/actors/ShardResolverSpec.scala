package unit.actors

import uk.gov.hmrc.customs.notification.actors.NotificationsActor
import uk.gov.hmrc.customs.notification.actors.model.NotificationCmd
import uk.gov.hmrc.customs.notification.model.ClientId
import uk.gov.hmrc.play.test.UnitSpec

class ShardResolverSpec extends UnitSpec {

  "shardResolver" should {
    "client id = fcff927b-11d4-41e9-87b1-bec27a275a40" in {
      NotificationsActor.shardResolver(msg("fcff927b-11d4-41e9-87b1-bec27a275a40")) shouldBe "77"
      NotificationsActor.shardResolver(msg("fcff927b-11d4-41e9-87b1-bec27a275a40")) shouldBe "77"
    }
    "client id = 2000" in {
      NotificationsActor.shardResolver(msg("db86f737-841b-4904-ac7b-31bbac45280a")) shouldBe "12"
      NotificationsActor.shardResolver(msg("db86f737-841b-4904-ac7b-31bbac45280a")) shouldBe "12"
    }
  }

  "idExtractor" should {
    "client id = 1000" in {
      val theMsg = msg("fcff927b-11d4-41e9-87b1-bec27a275a40")
      NotificationsActor.idExtractor(theMsg) shouldBe ("fcff927b-11d4-41e9-87b1-bec27a275a40", theMsg)
    }
  }

  private def msg(cid: ClientId) = new NotificationCmd {
    override val clientId: ClientId = cid
  }

}
