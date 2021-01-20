package controllers

import com.patson.data.{AirlineSource, AirportSource, AllianceSource, CycleSource, DelegateSource, LinkSource}
import com.patson.model.FlightType._
import com.patson.model._
import com.patson.model.airplane._
import com.patson.util.{AirportCache, ChampionUtil, CountryCache}

import scala.collection.mutable.ListBuffer
import scala.math.BigDecimal.RoundingMode
import scala.util.Random


object NegotiationUtil {
  val NEW_LINK_BASE_COST = 100
  val MAX_ASSIGNED_DELEGATE = 10
  val FREE_LINK_THRESHOLD = 5 //for newbie
  val FREE_LINK_FREQUENCY_THRESHOLD = 5
  val FREE_LINK_DIFFICULTY_THRESHOLD = 10
  val GREAT_SUCCESS_THRESHOLD = 0.95 // 5%


  def negotiate(info : NegotiationInfo, delegateCount : Int) = {
    val odds = info.odds.get(delegateCount) match {
      case Some(value) => value
      case None => 0
    }
    val number = Math.random()
    NegotiationResult(1 - odds, number)
  }



  val NO_NEGOTIATION_REQUIRED = NegotiationInfo(List (), List (), List (), List (), 0, 0, 0, Map(0 -> 1))


  val normalizedCapacity : LinkClassValues => Double = (capacity : LinkClassValues) => {
    capacity(ECONOMY) * ECONOMY.spaceMultiplier + capacity(BUSINESS) * BUSINESS.spaceMultiplier + capacity(FIRST) * FIRST.spaceMultiplier
  }



//  val getLinkLimit = (base : Option[AirlineBase]) => base match {
//    case Some(base) =>
//      val titlesByCountryCode: Map[String, Title.Value] = CountrySource.loadCountryAirlineTitlesByCriteria(List(("airline", base.airline.id))).map(entry => (entry.country.countryCode, entry.title)).toMap
//      base.getLinkLimit(titlesByCountryCode.get(base.countryCode))
//    case None => 0 //should not happen
//  }

  def getFromAirportRequirements(airline : Airline, newLink : Link, existingLinkOption : Option[Link], airlineLinks : List[Link]) = {
    import NegotiationRequirementType._
    val requirements = ListBuffer[NegotiationRequirement]()
    val isNewLink = existingLinkOption.isEmpty
    val airport = newLink.from

    val officeStaffCount : Int = airline.getBases().find(_.airport.id == airport.id).map(_.getOfficeStaffCapacity).getOrElse(0)
    val airlineLinksFromThisAirport = airlineLinks.filter(link => link.from.id == airport.id && (isNewLink || link.id != existingLinkOption.get.id))
    val currentOfficeStaffUsed = airlineLinksFromThisAirport.map(_.getFutureOfficeStaffRequired).sum
    val newOfficeStaffRequired = newLink.getFutureOfficeStaffRequired
    val newTotal = currentOfficeStaffUsed + newOfficeStaffRequired

    if (newTotal < officeStaffCount) {
      requirements.append(NegotiationRequirement(LINK_CAP, 0, s"Requires ${newOfficeStaffRequired} office staff, within your base capacity : ${newTotal} / ${officeStaffCount}"))
    } else {
      val requirement = (newTotal - officeStaffCount).toDouble / 20
      requirements.append(NegotiationRequirement(LINK_CAP, requirement, s"Requires ${newOfficeStaffRequired} office staff, over your base capacity : ${newTotal} / ${officeStaffCount}"))
    }

    val newFrequency = newLink.futureFrequency()
    val frequencyDelta = newFrequency - existingLinkOption.map(_.futureFrequency()).getOrElse(0)
    if (frequencyDelta > 0) {
      val maxFrequency = FlightType.getCategory(newLink.flightType) match {
          case FlightCategory.DOMESTIC => 45
          case FlightCategory.INTERNATIONAL => 40
          case FlightCategory.INTERCONTINENTAL => 30
      }

      getMaxFrequencyByModel(newLink.getAssignedModel().get, airport) match {
        case None =>
          if (newFrequency > maxFrequency) {
            requirements.append(NegotiationRequirement(EXCESSIVE_FREQUENCY, newFrequency - maxFrequency, s"Excessive frequency $newFrequency over allowed $maxFrequency"))
          }
        case Some(FrequencyRestrictionByModel(threshold, frequencyRestriction)) =>
          if (newFrequency > frequencyRestriction) {
            requirements.append(NegotiationRequirement(EXCESSIVE_FREQUENCY, newFrequency - frequencyRestriction, s"${airport.displayText} prefers not to have airplane < $threshold capacity with frequency > $frequencyRestriction"))
          }

      }


    }




    requirements.toList
  }

  /**
    * What is the max preferred frequency on an airport by the airplane size.
    *
    * The bigger the airport the more "strict" it is - it wants bigger airplane
    * @param airplaneModel
    * @param airport
    */
  def getMaxFrequencyByModel(airplaneModel : Model, airport : Airport) = {
    if (airport.size <= 3) { //too small to care
      None
    } else {
      val thresholdCapacity = 30 * (airport.size - 3) //airport would complain about any model lower than this threshold
      if (airplaneModel.capacity >= thresholdCapacity) {
        None
      } else {
        val frequencyRestriction = 14
        Some(FrequencyRestrictionByModel(thresholdCapacity, frequencyRestriction))
      }
    }

  }

  case class FrequencyRestrictionByModel(threshold : Int, frequencyRestriction : Int)

  def getToAirportRequirements(airline : Airline, newLink : Link, existingLinkOption : Option[Link], airlineLinks : List[Link]) = {
    val newCapacity : LinkClassValues = newLink.futureCapacity()
    val newFrequency = newLink.futureFrequency()

    val existingCapacity = existingLinkOption.map(_.futureCapacity()).getOrElse(LinkClassValues.getInstance())
    val existingFrequency = existingLinkOption.map(_.futureFrequency()).getOrElse(0)

    val capacityDelta = normalizedCapacity(newCapacity - existingCapacity)
    val frequencyDelta = newFrequency - existingFrequency
    val requirements = ListBuffer[NegotiationRequirement]()

    val flightTypeMultiplier = Computation.getFlightType(newLink.from, newLink.to) match {
      case SHORT_HAUL_DOMESTIC => 1
      case LONG_HAUL_DOMESTIC => 1.5
      case SHORT_HAUL_INTERNATIONAL => 2
      case LONG_HAUL_INTERNATIONAL => 2.5
      case SHORT_HAUL_INTERCONTINENTAL => 4
      case MEDIUM_HAUL_INTERCONTINENTAL => 5
      case LONG_HAUL_INTERCONTINENTAL => 5
      case ULTRA_LONG_HAUL_INTERCONTINENTAL => 5
    }
    val NEW_LINK_BASE_REQUIREMENT = 1
    val UPDATE_BASE_REQUIREMENT = 0.3

    import NegotiationRequirementType._

    if (existingLinkOption.isEmpty) {
      requirements.append(NegotiationRequirement(NEW_LINK, NEW_LINK_BASE_REQUIREMENT * flightTypeMultiplier, "New Flights"))
    } else {
      requirements.append(NegotiationRequirement(UPDATE_LINK, UPDATE_BASE_REQUIREMENT * flightTypeMultiplier, "Update Flights"))
    }

    if (capacityDelta > 0) {
      val capacityChangeCost = capacityDelta.toDouble / 2000
      requirements.append(NegotiationRequirement(INCREASE_CAPACITY, capacityChangeCost * flightTypeMultiplier, s"Capacity increment : $capacityDelta"))
    }

    if (frequencyDelta > 0) {
      val frequencyChangeCost = frequencyDelta.toDouble / 3
      requirements.append(NegotiationRequirement(INCREASE_FREQUENCY, frequencyChangeCost, s"Frequency increment : $frequencyDelta"))
    }

    getMaxFrequencyByModel(newLink.getAssignedModel().get, newLink.to).foreach { entry =>
      if (newFrequency > entry.frequencyRestriction) {
        requirements.append(NegotiationRequirement(EXCESSIVE_FREQUENCY, newFrequency - entry.frequencyRestriction, s"${newLink.to.displayText} prefers not to have airplane < ${entry.threshold} capacity with frequency > ${entry.frequencyRestriction}"))
      }
    }

    //val odds = new NegotiationOdds()
    existingLinkOption match {
      case Some(link) => //then consider existing load factor of this link
        val loadFactor : Double =(link.getTotalCapacity - link.getTotalSoldSeats).toDouble / link.getTotalCapacity
        if (loadFactor < 0.8) {
          val cost = Math.ceil(1 + (0.8 - loadFactor * 4))
          requirements.append(NegotiationRequirement(LOW_LOAD_FACTOR, cost, s"Low load factor ${BigDecimal(loadFactor * 100).setScale(2)}%"))
        }

      case None => //let's not make it too hard for newcomer for now
      //      case None => //existing competition
      //        //consider how many existing routes - if more than 2 reduce the odds
      //        val competingLinks = LinkSource.loadLinksByAirports(newLink.from.id, newLink.to.id, LinkSource.ID_LOAD) ++ LinkSource.loadLinksByAirports(newLink.to.id, newLink.from.id, LinkSource.ID_LOAD)
      //        val competingLinksCount = competingLinks.filter(_.capacity.total > 0).size
      //        if (competingLinksCount >= 2) {
      //          requirements.append(NegotiationRequirement(EXISTING_COMPETITION, competingLinksCount - 2))
      //        }
        val airport = newLink.to
        CountryCache.getCountry(airport.countryCode).foreach { country =>
          airline.getCountryCode().foreach { homeCountryCode =>
            if (homeCountryCode != airport.countryCode) { //closed country are anti foreign airlines
              val baseForeignAirline = (14 - country.openness) * 0.5
              requirements.append(NegotiationRequirement(FOREIGN_AIRLINE, baseForeignAirline, "Foreign Airline"))
            }
          }

          val flightCategory = FlightType.getCategory(newLink.flightType)
          if (flightCategory != FlightCategory.DOMESTIC) {
            airport.getFeatures().find(_.featureType == AirportFeatureType.GATEWAY_AIRPORT) match {
              case Some(_) => //OK
              case None =>
                val nonGatewayCost = (14 - country.openness) * 0.15 * flightTypeMultiplier
                requirements.append(NegotiationRequirement(NON_GATEWAY, nonGatewayCost, "International flight to non-gateway"))
            }
          }
        }



    }
    requirements.toList
  }
  val getStaffRequired = (link : Link) => {
    Computation.getFlightType(link.from, link.to) match {
      case SHORT_HAUL_DOMESTIC => 5
      case LONG_HAUL_DOMESTIC => 8
      case SHORT_HAUL_INTERNATIONAL => 10
      case LONG_HAUL_INTERNATIONAL => 12
      case SHORT_HAUL_INTERCONTINENTAL => 12
      case MEDIUM_HAUL_INTERCONTINENTAL => 20
      case LONG_HAUL_INTERCONTINENTAL => 30
      case ULTRA_LONG_HAUL_INTERCONTINENTAL => 30
    }
  }

  val getBaseStaffCount = (base : AirlineBase) =>  {

  }

  def getNegotiationRequirements(newLink : Link, existingLinkOption : Option[Link], airline : Airline, airlineLinks : List[Link]) = {
    val fromAirportRequirements : List[NegotiationRequirement] = getFromAirportRequirements(airline, newLink, existingLinkOption, airlineLinks)
    val toAirportRequirements : List[NegotiationRequirement] = getToAirportRequirements(airline, newLink, existingLinkOption, airlineLinks)

    (fromAirportRequirements, toAirportRequirements)
  }

  def getNegotiationDiscounts(airport : Airport, airline : Airline, allianceMembers : List[AllianceMember]) = {
    val discounts = ListBuffer[NegotiationDiscount]()
    //how busy is this airport
    //val airportLinks =  LinkSource.loadLinksByFromAirport(airport.id) ++ LinkSource.loadLinksByToAirport(airport.id)
    //val totalFrequency = airportLinks.map(_.frequency).sum
    import NegotiationDiscountType._
    /*
    if (totalFrequency <= 50 * airport.size) { //under serve
      discounts.append(NegotiationDiscount(BELOW_CAPACITY, 0.2)) //20%
    } else if (totalFrequency >= 300 * airport.size) {
      //penalty multiplier start from 1 up to 10
      val multiplier = Math.min(10, 1 + (totalFrequency - 300 * airport.size).toDouble / (100 * airport.size))
      discounts.append(NegotiationDiscount(OVER_CAPACITY, -0.2 * multiplier)) //crowded, start from 20% to up to 200% penalty
    }
    */


    // 20 -> 0.2
    // 50 -> 0.2 + 0.15
    // 100 -> 0.35 + 0.15
    val relationship = AirlineCountryRelationship.getAirlineCountryRelationship(airport.countryCode, airline).relationship
    if (relationship >= 0) {
      var discount = relationship * 0.01
//        if (relationship <= 20) {
//          relationship * 0.01
//        } else if (relationship <= 50) {
//          0.2 + (relationship - 20) * 0.005
//        } else {
//          0.35 + (relationship - 50) * 0.003
//        }

      discount = Math.min(discount, 0.7)
      discounts.append(NegotiationDiscount(COUNTRY_RELATIONSHIP, discount))
    } else if (relationship < 0) { //very penalizing
      val discount = relationship * 0.1
      discounts.append(NegotiationDiscount(COUNTRY_RELATIONSHIP, discount))
    }

    val loyalty = airport.getAirlineLoyalty(airline.id)
    val MAX_LOYALTY_DISCOUNT = 0.5
    if (loyalty > 0) {
      val discount = Math.min(MAX_LOYALTY_DISCOUNT, MAX_LOYALTY_DISCOUNT * loyalty / AirlineAppeal.MAX_LOYALTY)
      discounts.append(NegotiationDiscount(LOYALTY, discount))
    }

    val airportChampionAirlineIds = ChampionUtil.loadAirportChampionInfoByAirport(airport.id).map(_.loyalist.airline.id)
    allianceMembers.foreach { allianceMember =>
      if (allianceMember.airline.getBases().map(_.airport.id).contains(airport.id)) {
        if (allianceMember.airline.id == airline.id) { //self, always get discount
          discounts.append(NegotiationDiscount(BASE, 0.2))
        } else if (airportChampionAirlineIds.contains(allianceMember.airline.id)) {
          discounts.append(NegotiationDiscount(ALLIANCE_BASE, 0.2))
        }
      }
    }


    discounts.toList
  }

  val MAX_TOTAL_DISCOUNT = 0.8 //at most 80% off

  def getLinkNegotiationInfo(airline : Airline, newLink : Link, existingLinkOption : Option[Link]) : NegotiationInfo = {
    val fromAirport : Airport = newLink.from
    val toAirport : Airport = newLink.to
    val newCapacity : LinkClassValues = newLink.futureCapacity()
    val newFrequency = newLink.futureFrequency()

    val existingCapacity = existingLinkOption.map(_.futureCapacity()).getOrElse(LinkClassValues.getInstance())
    val existingFrequency = existingLinkOption.map(_.futureFrequency()).getOrElse(0)

    val capacityDelta = normalizedCapacity(newCapacity - existingCapacity)
    val frequencyDelta = newFrequency - existingFrequency

    val airlineLinks = LinkSource.loadLinksByAirlineId(airline.id)

    //reduction of service is always okay for now
    if (capacityDelta <= 0 && frequencyDelta <= 0) {
      return NegotiationUtil.NO_NEGOTIATION_REQUIRED
    }

    val (fromAirportRequirements, toAirportRequirements) = getNegotiationRequirements(newLink, existingLinkOption, airline, airlineLinks)

    val allianceMembers = airline.getAllianceId() match {
      case Some(allianceId) => AllianceSource.loadAllianceById(allianceId, true).get.members
      case None => List.empty
    }
    val fromAirportDiscounts = getNegotiationDiscounts(fromAirport, airline, allianceMembers)
    val toAirportDiscounts = getNegotiationDiscounts(toAirport, airline, allianceMembers)

    val fromRequirementBase = fromAirportRequirements.map(_.value).sum
    val toRequirementBase = toAirportRequirements.map(_.value).sum
    val totalFromDiscount = Math.min(MAX_TOTAL_DISCOUNT, fromAirportDiscounts.map(_.value).sum)
    val totalToDiscount = Math.min(MAX_TOTAL_DISCOUNT, toAirportDiscounts.map(_.value).sum)
    val fromAirportRequirementValue = fromRequirementBase * (1 - totalFromDiscount)
    val toAirportRequirementValue = toRequirementBase * (1 - totalToDiscount)
    val finalRequirementValue = fromAirportRequirementValue + toAirportRequirementValue

    //check for freebie bonus
    if (airlineLinks.length < FREE_LINK_THRESHOLD &&
      newFrequency < FREE_LINK_FREQUENCY_THRESHOLD &&  //to prevent many small increase
      finalRequirementValue < FREE_LINK_DIFFICULTY_THRESHOLD &&
      FlightType.getCategory(newLink.flightType) != FlightCategory.INTERCONTINENTAL
    ) {
      return NegotiationUtil.NO_NEGOTIATION_REQUIRED
    }

    val info = NegotiationInfo(fromAirportRequirements, toAirportRequirements, fromAirportDiscounts, toAirportDiscounts, totalFromDiscount, totalToDiscount, finalRequirementValue, computeOdds(finalRequirementValue, Math.min(MAX_ASSIGNED_DELEGATE, airline.getDelegateInfo.availableCount)))
    return info
  }

  /**
    *
    * @param finalRequirementValue
    * @param maxDelegateCount
    * @return a map of delegate count vs odds, which 0 <= odds <= 1
    */
  def computeOdds(finalRequirementValue : Double, maxDelegateCount : Int) : Map[Int, Double] = {
    val requiredDelegates = finalRequirementValue
    var accumulativeOdds = 0.0
    var foundMax = false
    (0 to maxDelegateCount).map { delegateCount =>
      val base = Math.min(1, (15 - requiredDelegates) * 0.04 / (requiredDelegates / Math.ceil(requiredDelegates)))
      val oddsPerBaseDelegate = base / (Math.max(1, requiredDelegates))
      val oddsForThisDelegateCount : Double =
        if (finalRequirementValue == 0) {
          1
        } else {
          if (delegateCount < requiredDelegates) {
            0
          } else {

            if (delegateCount < requiredDelegates + 1) {
              accumulativeOdds = base
            } else {

              accumulativeOdds = Math.min(1, accumulativeOdds +  oddsPerBaseDelegate * Math.pow(0.95, delegateCount - requiredDelegates.toInt))
            }
            accumulativeOdds
          }
        }
      (delegateCount, oddsForThisDelegateCount)
    }.filter { //keep only the first count that reaches 1
      case (delegateCount, oddsForThisDelegateCount) =>
        if (foundMax) {
          false
        } else {
          foundMax = (oddsForThisDelegateCount == 1)
          true
        }
    }.toMap
  }

  def getLinkBonus(link : Link, capacityDelta : Int, delegates : List[BusyDelegate]) : NegotiationBonus = {
    val monetaryBaseValue = link.standardPrice(ECONOMY) * capacityDelta
    NegotiationBonus.drawBonus(monetaryBaseValue, delegates, link.to)
  }

}

//class NegotiationOdds() {
//  private[this] val factors = scala.collection.mutable.LinkedHashMap[NegotationFactor.Value, Double]()
//  def addFactor(factor : NegotationFactor.Value, value : Double) = {
//    factors.put(factor, value)
//  }
//
//  def value = factors.values.sum match {
//    case x if x > 1 => 1.0
//    case x if x < 0 => 0.0
//    case x => x
//  }
//
//  def getFactors : Map[NegotationFactor.Value, Double] = factors.toMap
//}

//object NegotationFactor extends Enumeration {
//  type NegotationFactor = Value
//  val COUNTRY_RELATIONSHIP, EXISTING_LINKS, INITIAL_LINKS, DECREASE_CAPACITY, INCREASE_CAPACITY, OTHER = Value
//
//  def description(factor : NegotationFactor) =  factor match {
//    case COUNTRY_RELATIONSHIP => "Country Relationship"
//    case EXISTING_LINKS => "Existing Routes by other Airlines"
//    case INITIAL_LINKS => "Bonus for smaller Airlines"
//    case INCREASE_CAPACITY => "Increase Capacity"
//    case DECREASE_CAPACITY => "Decrease Capacity"
//    case OTHER => "Unknown"
//  }
//}

case class NegotiationInfo(fromAirportRequirements : List[NegotiationRequirement], toAirportRequirements : List[NegotiationRequirement], fromAirportDiscounts : List[NegotiationDiscount], toAirportDiscounts : List[NegotiationDiscount], finalFromDiscountValue : Double, finalToDiscountValue : Double, finalRequirementValue : Double, odds : Map[Int, Double])

object NegotiationRequirementType extends Enumeration {
  type NegotiationRequirementType = Value
  val FROM_COUNTRY_RELATIONSHIP, TO_COUNTRY_RELATIONSHIP, EXISTING_COMPETITION, NEW_LINK, UPDATE_LINK, INCREASE_CAPACITY, INCREASE_FREQUENCY, EXCESSIVE_FREQUENCY, LOW_LOAD_FACTOR, FOREIGN_AIRLINE, NON_GATEWAY, LINK_CAP, OTHER = Value

//  def description(requirementType : NegotiationRequirementType.Value, link : Link) =  requirementType match {
//    case EXISTING_COMPETITION => "Existing Routes by other Airlines"
//    case NEW_LINK => "New Flights"
//    case INCREASE_CAPACITY => "Increase Capacity"
//    case LOW_LOAD_FACTOR => "Low Load Factor"
//    case INCREASE_FREQUENCY => "Increase Frequency"
//    case FOREIGN_AIRLINE => "Foreign Airline"
//    case OTHER => "Unknown"
//  }
}

object NegotiationDiscountType extends Enumeration {
  type NegotiationDiscountType = Value
  val COUNTRY_RELATIONSHIP, BELOW_CAPACITY, OVER_CAPACITY, LOYALTY, BASE, ALLIANCE_BASE, NEW_AIRLINE = Value

  def description(adjustmentType : NegotiationDiscountType.Value, airport : Airport) =  adjustmentType match {
    case COUNTRY_RELATIONSHIP => s"Country Relationship with ${airport.countryCode}"
    case BELOW_CAPACITY => s"${airport.displayText} is under capacity"
    case OVER_CAPACITY => s"${airport.displayText} is over capacity"
    case LOYALTY => s"Loyalty of ${airport.displayText}"
    case BASE => s"Airline base"
    case ALLIANCE_BASE => s"Alliance member base as ranked champion "
    case NEW_AIRLINE => s"New airline bonus"
  }
}

case class NegotiationRequirement(requirementType : NegotiationRequirementType.Value, value : Double, description : String) {

}
case class NegotiationDiscount(adjustmentType : NegotiationDiscountType.Value, value : Double)

case class NegotiationResult(threshold : Double, result : Double) {
  val isSuccessful = result >= threshold
  val isGreatSuccess = isSuccessful && result >= NegotiationUtil.GREAT_SUCCESS_THRESHOLD
  println(s"negotiation result: threshold $threshold vs result $result. Great success ? $isGreatSuccess")

  val SESSION_COUNT = 10
  def getNegotiationSessions() : NegotiationSession = {
    //    val BASE_PASSING_SCORE = 100 //make it a more than 0...just for nicer display
    //
    val passingScore = 75 + threshold * 25

    val score = 75 + result * 25

    val average = score / SESSION_COUNT //average score for each session

    val sessionScores = ListBuffer[Double]()
    for (i <- 0 until SESSION_COUNT) {
      sessionScores.append(average)
    }
    //now generate randomness 100+ and 100- randomly assigned to each number
    for (i <- 0 until 10) {
      val index1 = Random.nextInt(SESSION_COUNT)
      val index2 = Random.nextInt(SESSION_COUNT)
      val variation = Random.nextInt(5)
      sessionScores(index1) = sessionScores(index1) + variation
      sessionScores(index2) = sessionScores(index2) - variation
    }
    NegotiationSession(passingScore, sessionScores.toList)
  }
}

case class NegotiationSession(passingScore : Double, sessionScores : List[Double])

object NegotiationBonus {
  val pool = List(
    NegotiationCashBonusTemplate(1), NegotiationCashBonusTemplate(2), NegotiationCashBonusTemplate(5), NegotiationCashBonusTemplate(10),
    NegotiationCooldownBonusTemplate(2), NegotiationCooldownBonusTemplate(4), NegotiationCooldownBonusTemplate(6),
    NegotiationAwarenessBonusTemplate(50),
    NegotiationLoyaltyBonusTemplate(1), NegotiationLoyaltyBonusTemplate(2), NegotiationLoyaltyBonusTemplate(5)
  )
  val random = new Random()
  def drawBonus(monetaryBaseValue : Long, delegates: List[BusyDelegate], airport : Airport): NegotiationBonus = {
    pool(random.nextInt(pool.size)).computeBonus(monetaryBaseValue, delegates, airport)
  }
}

abstract class NegotiationBonus {
  def description : String
  def apply(airline : Airline) : Unit
}

case class NegotiationCashBonus(cash : Long, description : String) extends NegotiationBonus {
  def apply(airline : Airline) = {
    AirlineSource.adjustAirlineBalance(airline.id, cash)
  }
}

case class NegotiationCooldownBonus(delegates : List[BusyDelegate], cooldownDiscount : Int, description : String) extends NegotiationBonus {
  def apply(airline : Airline) = {
    val updatingDelegates = delegates.map { delegate =>
      delegate.copy(availableCycle = delegate.availableCycle.map(_ - cooldownDiscount))
    }
    DelegateSource.updateBusyDelegateAvailableCycle(updatingDelegates)
  }
}

case class NegotiationAwarenessBonus(airport : Airport, awarenessBonus : Int, duration : Int, description : String) extends NegotiationBonus {
  def apply(airline : Airline) = {
    val cycle = CycleSource.loadCycle()
    AirportSource.saveAirlineAppealBonus(airport.id, airline.id, AirlineBonus(BonusType.NEGOTIATION_BONUS, AirlineAppeal(loyalty = 0, awareness = awarenessBonus), Some(cycle + duration)))
  }
}

case class NegotiationLoyaltyBonus(airport : Airport, loyaltyBonus: Double, duration : Int, description : String) extends NegotiationBonus {
  def apply(airline : Airline) = {
    val cycle = CycleSource.loadCycle()
    AirportSource.saveAirlineAppealBonus(airport.id, airline.id, AirlineBonus(BonusType.NEGOTIATION_BONUS, AirlineAppeal(loyalty = loyaltyBonus, awareness = 0), Some(cycle + duration)))
  }
}

abstract class NegotiationBonusTemplate {
  def computeBonus(monetaryBaseValue : Long, delegates : List[BusyDelegate], airport : Airport) : NegotiationBonus //monetaryBaseValue : for example for link, it would be standardPrice * capacityChange => 1 week of revenue
}

case class NegotiationCashBonusTemplate(factor : Int) extends NegotiationBonusTemplate {
  override def computeBonus(monetaryBaseValue : Long, delegates : List[BusyDelegate], airport : Airport) : NegotiationBonus = {
    val cash = monetaryBaseValue * factor
    val description =
      if (factor <= 1) {
        s"The airport authority decided to provide a modest subsidy of $$$cash!"
      } else if (factor <= 3) {
        s"The airport authority decided to provide a sizable subsidy of $$$cash!"
      } else if (factor <= 5) {
        s"The airport authority decided to provide a large subsidy of $$$cash!"
      } else {
        s"The airport authority decided to provide a generous subsidy of $$$cash!"
      }
    NegotiationCashBonus(cash, description)
  }
}

case class NegotiationCooldownBonusTemplate(discountCycle : Int) extends NegotiationBonusTemplate {
  override def computeBonus(monetaryBaseValue : Long, delegates : List[BusyDelegate], airport : Airport) : NegotiationBonus = {
    val description =
      if (discountCycle <= 3) {
        s"The delegates are encouraged by the success, cooldown reduced by $discountCycle weeks!"
      } else {
        s"The delegates are energized by the great success, cooldown reduced by $discountCycle weeks!"
      }
    NegotiationCooldownBonus(delegates, discountCycle, description)
  }
}

case class NegotiationAwarenessBonusTemplate(awarenessBonus : Int) extends NegotiationBonusTemplate {
  val duration = 52
  override def computeBonus(monetaryBaseValue : Long, delegates : List[BusyDelegate], airport : Airport) : NegotiationBonus = {
    val description = s"Media coverage increases awareness by $awarenessBonus at ${airport.displayText} for $duration weeks"
    NegotiationAwarenessBonus(airport, awarenessBonus, duration, description)
  }
}

case class NegotiationLoyaltyBonusTemplate(bonusFactor : Int) extends NegotiationBonusTemplate {
  val duration = 52
  val MAX_BONUS = 20
  override def computeBonus(monetaryBaseValue : Long, delegates : List[BusyDelegate], airport : Airport) : NegotiationBonus = {
    val loyaltyBonus = BigDecimal(Math.min(20, monetaryBaseValue.toDouble / airport.power * 1000000)).setScale(2, RoundingMode.HALF_UP)
    val description =
      if (loyaltyBonus <= 2) {
        s"Some media coverage increases loyalty by $loyaltyBonus at ${airport.displayText} for $duration weeks"
      } else if (loyaltyBonus <= 5) {
        s"Great media coverage increases loyalty by $loyaltyBonus at ${airport.displayText} for $duration weeks"
      } else {
        s"Extensive media coverage increases loyalty by $loyaltyBonus at ${airport.displayText} for $duration weeks"
      }
    NegotiationLoyaltyBonus(airport, loyaltyBonus.toDouble, duration, description)
  }
}







