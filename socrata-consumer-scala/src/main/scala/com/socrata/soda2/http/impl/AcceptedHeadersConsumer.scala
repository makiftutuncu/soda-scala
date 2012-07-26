package com.socrata.soda2.http
package impl

import scala.{collection => sc}

import com.rojoma.json.ast.{JString, JObject, JValue}

import com.socrata.http.{BodyConsumer, HeadersConsumer}

import HeadersConsumerUtils._

private [http] class AcceptedHeadersConsumer(defaultRetryAfter: Int) extends HeadersConsumer[Retryable[Nothing]] {
  def apply(headers: sc.Map[String, Seq[String]]): Left[BodyConsumer[Retryable[Nothing]], Nothing] = {
    val codec = jsonCodec(headers)
    headers.get("X-SODA2-Location").map(_.last) match {
      case Some(url) =>
        val retryAfter = headers.get("X-SODA2-Retry-After").map(_.last).map(_.toInt).getOrElse(defaultRetryAfter)
        Left(new SingleJValueBodyConsumer(codec).map(new202(url, retryAfter, _)))
      case None =>
        // legacy; have to read a JSON object and see if there's a token in it
        Left(new SingleJValueBodyConsumer(codec).map(old202(_)))
    }
  }

  def new202(url: String, retryAfter: Int, body: JValue): Retryable[Nothing] = body match {
    case body: JObject =>
      Left(Redirect(url, retryAfter, extractDetails(body)))
    case _ =>
      error("NYI") // protocol error: this should ALWAYS be an Object!
  }

  def extractDetails(body: JObject): JObject =
    body.get("details") match {
      case Some(details: JObject) => details
      case None => JObject(Map.empty)
      case Some(_) => error("NYI") // protocol error
    }

  def old202(body: JValue): Retryable[Nothing] = body match {
    case body@JObject(fields) =>
      val details = extractDetails(body)
      val action = fields.get("ticket") match {
        case Some(JString(ticket)) =>
          RetryWithTicket(ticket, defaultRetryAfter, details)
        case Some(_) =>
          error("NYI") // protocol error
        case None =>
          Retry(defaultRetryAfter, details)
      }
      Left(action)
    case _ =>
      error("NYI") // protocol error: this should ALWAYS be an Object!
  }
}
