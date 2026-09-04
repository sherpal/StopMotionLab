package be.doeraene.services.database

import be.doeraene.utils.testshenanigans.OnlyInTest

def deleteDatabase(service: DatabaseService)(using OnlyInTest): Unit =
  service.deleteDatabase()