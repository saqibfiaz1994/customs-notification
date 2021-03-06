import sbt._

object AppDependencies {

  private val hmrcTestVersion = "3.4.0-play-25"
  private val scalaTestVersion = "3.0.5"
  private val scalatestplusVersion = "2.0.1"
  private val mockitoVersion = "2.23.4"
  private val wireMockVersion = "2.20.0"
  private val customsApiCommonVersion = "1.36.0"
  private val workItemRepoVersion = "5.2.0"
  private val playReactivemongoVersion = "6.2.0"
  private val mongoLockVersion = "5.1.0"
  private val testScope = "test,it"

  val playReactiveMongo = "uk.gov.hmrc" %% "play-reactivemongo" % playReactivemongoVersion

  val mongoLock = "uk.gov.hmrc" %% "mongo-lock" % mongoLockVersion

  val hmrcTest = "uk.gov.hmrc" %% "hmrctest" % hmrcTestVersion % testScope

  val scalaTest = "org.scalatest" %% "scalatest" % scalaTestVersion % testScope

  val scalaTestPlusPlay = "org.scalatestplus.play" %% "scalatestplus-play" % scalatestplusVersion % testScope

  val wireMock = "com.github.tomakehurst" % "wiremock" % wireMockVersion % testScope exclude("org.apache.httpcomponents","httpclient") exclude("org.apache.httpcomponents","httpcore")

  val mockito =  "org.mockito" % "mockito-core" % mockitoVersion % testScope

  val customsApiCommon = "uk.gov.hmrc" %% "customs-api-common" % customsApiCommonVersion

  val workItemRepo = "uk.gov.hmrc" %% "work-item-repo" % workItemRepoVersion

  val customsApiCommonTests = "uk.gov.hmrc" %% "customs-api-common" % customsApiCommonVersion % testScope classifier "tests"

  val reactiveMongoTest = "uk.gov.hmrc" %% "reactivemongo-test" % "3.1.0" % testScope

}
