package aktors

import java.net.URL
import java.util.function.Consumer

import akka.actor.UntypedActor
import aktors.messages._
import com.mongodb.casbah.Imports._
import scala.concurrent.ExecutionContext.Implicits.global

import scala.concurrent.Future

/**
 * Created by Patrick Robinson on 02.05.15.
 */
class DB(database : String = "loadgen") extends UntypedActor {
	val client = MongoClient() // TODO multiple Verbindungen?
	val db = client(database)
	val testruncoll = db("testrun")
	val testplancoll = db("testplan")
	val usercoll = db("user")

	@throws[Exception](classOf[Exception])
	override def onReceive(message: Any): Unit = {
		message match {
			case workerraw : LoadWorkerRaw => Future {
				val query = MongoDBObject("_id" -> workerraw.getTestrun.getID)
				val run = MongoDBObject("start" -> workerraw.getStart, "end" -> workerraw.getEnd, "iter" -> workerraw.getIterOnWorker)
				val update = $push("runs" -> run)
				testruncoll.update(query, update) // TODO imperformant?
			}
			case trun : Testrun => Future { testruncoll.insert(MongoDBObject("_id" -> trun.getID, "testPlanId" -> trun.getTestplan.getID)) }
			case tplan : Testplan => Future { testplancoll.insert(MongoDBObject(
				"_id" -> tplan.getID
			,	"numRuns" -> tplan.getNumRuns
			,	"parallelity" -> tplan.getParallelity
			,	"path" -> tplan.getPath.toString
			,	"waitBetweenMsgs" -> tplan.getWaitBetweenMsgs
			,	"waitBeforeStart" -> tplan.getWaitBeforeStart
			,	"connectionType" -> tplan.getConnectionType.toString
			,	"user" -> tplan.getUser.getID
			)) }
			case user : User => Future { usercoll.insert(MongoDBObject( // TODO !! what if user exists already?
				"_id" -> user.getID
			,	"name" -> user.getName
			,   "password" -> user.getPassword
			)) }
			case getCMD : DBGetCMD => {
				val function = (x:AnyRef) => if(getCMD.callback == null) getSender().tell(x, getSelf()) else getCMD.callback.accept(x)
				getCMD.t match {
					case DBGetCMD.Type.AllPlansForUser => testplancoll.find("user" `=` getCMD.id).foreach(planDocument => function(convertPlan(planDocument)))
					case DBGetCMD.Type.AllRunsForPlan => testruncoll.find("testPlanId" `=` getCMD.id).foreach(runDocument => function(convertRun(runDocument)))
					case DBGetCMD.Type.PlanByID => function(getPlan(getCMD.id))
					case DBGetCMD.Type.RunByID => function(getRun(getCMD.id))
					case DBGetCMD.Type.UserByID => function(getUser(getCMD.id))
					case DBGetCMD.Type.RunRaws => {
						val testrunobj = testruncoll.findOneByID(getCMD.id).get
						val testrunF : Future[Testrun] = Future { convertRun(testrunobj) }
						testrunobj.getAs[MongoDBList]("runs").get.foreach(obj => {
							val raw = obj.asInstanceOf[BasicDBObject]
							function(new LoadWorkerRaw(testrunF, raw.getAs[Int]("iter").get, raw.getAs[Long]("start").get, raw.getAs[Long]("end").get))
						})
					}
				}
			}
			case query : DBQuery => query.t match {
				case DBQuery.Type.Login => {
					val result = usercoll.findOne(MongoDBObject("name" -> query.terms.get("name"))).getOrElse(null) // TODO no user -> None.get, FIX: if None -> flag = false else get
					if(result == null) {
						query.flag = false
					} else {
						val user = new User(
							result.getAs[ObjectId]("_id").get
							, result.getAs[String]("name").get
							, result.getAs[String]("password").get
						)
						query.flag = user.check(query.terms.get("password"))
						if (query.flag) query.result = user
					}
					getSender.tell(query, getSelf)
				}
				case _ => return
			}
			case del : DBDelCMD => {
				var coll = usercoll
				del.t match {
					case DBDelCMD.Type.Plan => coll = testplancoll
					case DBDelCMD.Type.Run => coll = testruncoll
					case DBDelCMD.Type.User => coll = usercoll
					case DBDelCMD.Type.DB => {
						testruncoll.drop
						testplancoll.drop
						usercoll.drop
						return
					}
					case _ => return
				}
				coll.findAndRemove(MongoDBObject("_id" -> del.id))
			}
			case simple : String => simple match {
				case "close" => {
					client.close()
					// DEBUG deadletters getContext.stop(getSelf)
				}
				case _ => unhandled(message)
			}
			case _ => unhandled(message)
		}

		def getUser(id : ObjectId) : User = {
			// TODO cache?
			val userDocument = usercoll.findOneByID(id).get
			return new User(
				id
			,   userDocument.getAs[String]("name").get
			,	userDocument.getAs[String]("password").get
			)
		}

		// TODO utilise currentuser, when account subsystem is implemented?
		def convertPlan(document : testplancoll.T) : Testplan = new Testplan(
			User = Future { getUser(document.getAs[ObjectId]("user").get) },
			ID = document.getAs[ObjectId]("_id").get,
			WaitBeforeStart = document.getAs[Int]("waitBeforeStart").get,
			WaitBetweenMsgs = document.getAs[Int]("waitBetweenMsgs").get,
			Parallelity = document.getAs[Int]("parallelity").get,
			NumRuns = document.getAs[Int]("numRuns").get,
			Path = new URL(document.getAs[String]("path").get),
			ConType = ConnectionType.valueOf(document.getAs[String]("connectionType").get)
		)

		def getPlan(id : ObjectId) : Testplan = convertPlan(testplancoll.findOneByID(id).get)

		def getRun(id : ObjectId) : Testrun = convertRun(testruncoll.findOneByID(id).get)

		def convertRun(runDocument : testruncoll.T) : Testrun = new Testrun(
				ID = runDocument.getAs[ObjectId]("_id").get,
				Testplan = Future {getPlan(runDocument.getAs[ObjectId]("testPlanId").get)}
		)
	}
}
