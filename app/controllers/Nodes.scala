/* 
** Copyright [2012-2013] [Megam Systems]
**
** Licensed under the Apache License, Version 2.0 (the "License");
** you may not use this file except in compliance with the License.
** You may obtain a copy of the License at
**
** http://www.apache.org/licenses/LICENSE-2.0
**
** Unless required by applicable law or agreed to in writing, software
** distributed under the License is distributed on an "AS IS" BASIS,
** WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
** See the License for the specific language governing permissions and
** limitations under the License.
*/
package controllers

import scalaz._
import Scalaz._
import scalaz.NonEmptyList._

import scalaz.Validation._
import play.api._
import play.api.mvc._
import play.api.mvc.SimpleResult
import models._
import controllers.stack._
import controllers.stack.APIAuthElement
import controllers.funnel.{ FunnelResponse, FunnelResponses }
import controllers.funnel.FunnelErrors._
import org.megam.common.amqp._

/**
 * @author ram
 *
 */
object Nodes extends Controller with APIAuthElement {

  /*
   * parse.tolerantText to parse the RawBody 
   * get requested body and put into the riak bucket
   */
  def post = StackAction(parse.tolerantText) { implicit request =>
    play.api.Logger.debug(("%-20s -->[%s]").format("controllers.Node", "post:Entry"))

    (Validation.fromTryCatch[SimpleResult] {
      reqFunneled match {
        case Success(succ) => {
          val freq = succ.getOrElse(throw new Error("Request wasn't funneled. Verify the header."))
          val email = freq.maybeEmail.getOrElse(throw new Error("Email not found (or) invalid."))
          val clientAPIBody = freq.clientAPIBody.getOrElse(throw new Error("Body not found (or) invalid."))
          play.api.Logger.debug(("%-20s -->[%s]").format("controllers.Node", "request funneled."))
          models.Nodes.createMany(email, clientAPIBody) match {
            case Success(succ) => {
              val chainedComps = (succ.list filter (nelwop => nelwop.isDefined) map { //filter None, as foldRight creates it.
                nrOpt: Option[NodeProcessedResult] =>
                  (nrOpt.map { nr =>
                    CloudStandUpPublish(nr.key, nr.req_id).dop.flatMap { x =>
                      play.api.Logger.debug(("%-20s -->[%s] %s").format("controllers.Node", "published successfully.", nr.key + " " + nr.req_id))
                      FunnelResponse(CREATED, """Node initiation instruction submitted successfully.
            |
            |Check back on the {:node_name=>'%s', :req_id=>'%s'}
            |The cloud is working for you. It will be ready shortly.""".format(nr.key, nr.req_id).stripMargin, "Megam::Node").successNel[Throwable]
                    } match {
                      //this is only a temporary hack.
                      case Success(succ_cpc) => succ_cpc
                      case Failure(err) =>
                        FunnelResponse(BAD_REQUEST, """Node initiation submission failed.   
            |for 'node name':{%s} 'request_id':{%s}
            |Retry again, our queue servers isn't running or maxed""".format(nr.key, nr.req_id).stripMargin, "Megam::Node")
                    }
                  })
              }).map { fr =>
                {
                  fr.getOrElse(FunnelResponse(BAD_REQUEST, """Node initiation submission failed.
            |for 'node name':{%s} 'request_id':{%s}
            |Retry again, our cloud api servers barfed""".format("", "").stripMargin, "Megam::Node"))
                }
              }
              Status(CREATED)(FunnelResponses.toJson(FunnelResponses(chainedComps), true))
            }

            case Failure(err) => {
              val rn: FunnelResponse = new HttpReturningError(err)
              Status(rn.code)(rn.toJson(true))
            }
          }
        }
        case Failure(err) => {
          val rn: FunnelResponse = new HttpReturningError(err)
          Status(rn.code)(rn.toJson(true))
        }
      }
    }).fold(succ = { a: SimpleResult => a }, fail = { t: Throwable => Status(BAD_REQUEST)(t.getMessage) })
  }

  /*
   * GET: findByNodeName: Show a particular node name for an email
   * Email grabbed from header
   * Output: JSON (NodeResult)  
   **/
  def show(id: String) = StackAction(parse.tolerantText) { implicit request =>
    play.api.Logger.debug(("%-20s -->[%s]").format("controllers.Node", "show:Entry"))
    play.api.Logger.debug(("%-20s -->[%s]").format("nodename", id))

    (Validation.fromTryCatch[SimpleResult] {
      reqFunneled match {
        case Success(succ) => {
          play.api.Logger.debug(("%-20s -->[%s]").format("-------------------->", succ))
          val freq = succ.getOrElse(throw new Error("Request wasn't funneled. Verify the header."))
          val email = freq.maybeEmail.getOrElse(throw new Error("Email not found (or) invalid."))
          play.api.Logger.debug(("%-20s -->[%s]").format("controllers.Node", "request funneled."))

          models.Nodes.findByNodeName(List(id).some) match {
            case Success(succ) =>
              play.api.Logger.debug(("%-20s -->[%s]").format("-------------------->", succ))
              Ok(NodeResults.toJson(succ, true))
            case Failure(err) =>
              val rn: FunnelResponse = new HttpReturningError(err)
              Status(rn.code)(rn.toJson(true))
          }
        }
        case Failure(err) => {
          val rn: FunnelResponse = new HttpReturningError(err)
          Status(rn.code)(rn.toJson(true))
        }
      }
    }).fold(succ = { a: SimpleResult => a }, fail = { t: Throwable => Status(BAD_REQUEST)(t.getMessage) })

  }

  /*
   * GET: findbyEmail: List all the nodes names per email
   * Email grabbed from header.
   * Output: JSON (NodeResult)
   */
  def list = StackAction(parse.tolerantText) { implicit request =>
    (Validation.fromTryCatch[SimpleResult] {
      reqFunneled match {
        case Success(succ) => {
          val freq = succ.getOrElse(throw new Error("Request wasn't funneled. Verify the header."))
          val email = freq.maybeEmail.getOrElse(throw new Error("Email not found (or) invalid."))
          models.Nodes.findByEmail(email) match {
            case Success(succ) => Ok(NodeResults.toJson(succ, true))
            case Failure(err) =>
              val rn: FunnelResponse = new HttpReturningError(err)
              Status(rn.code)(rn.toJson(true))
          }
        }
        case Failure(err) => {
          val rn: FunnelResponse = new HttpReturningError(err)
          Status(rn.code)(rn.toJson(true))
        }
      }
    }).fold(succ = { a: SimpleResult => a }, fail = { t: Throwable => Status(BAD_REQUEST)(t.getMessage) })
  }
}