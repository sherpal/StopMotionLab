package be.doeraene.utils.testshenanigans

trait HasTestPower {

  given onlyInTest: OnlyInTest = OnlyInTest.onlyInTest

}
