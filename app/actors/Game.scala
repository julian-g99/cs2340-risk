package actors

import akka.actor.{Actor, ActorRef, Props}
import common.{Resources, UniqueIdProvider, UniqueValueManager, Util}
import controllers._
import game.GameState
import game.mode.GameMode
import models.GameLobbyState.State
import models.{GameLobbyState, Player, PlayerSettings}
import play.api.Logger

import scala.collection.immutable.HashSet
import scala.collection.mutable

object Game extends UniqueIdProvider {
  // Methods for UniqueIdProvider
  override def idLength: Int = Resources.GameIdLength
  private val IdCharsSet: Set[Char] = HashSet() ++ Resources.GameIdChars
  override protected def generateId(len: Int): String =
    Util.randomString(len, Resources.GameIdChars)
  override protected def isIdChar(c: Char): Boolean = IdCharsSet.contains(c)

  // Actor factory methods
  def props: Props = Props[Game]
  def apply(id: String, hostInfo: PlayerSettings): Props =
    Props({
      new Game(Resources.GameMode, id, hostInfo)
    })
}

/**
  * Game actor that supervises a collection of connected clients that
  * may or may not have actually joined
  * @param gameMode The current game mode of the game lobby
  * @param id The unique game Id
  * @param hostInfo The PlayerSettings of the host
  */
class Game(val gameMode: GameMode, val id: String, hostInfo: PlayerSettings)
  extends Actor with UniqueValueManager[PlayerSettings] {

  val logger: Logger = Logger(this.getClass)

  // *************
  // Mutable state
  // *************

  // The PlayerSettings of the initial host (submitted through the form
  // on the landing page)
  var initialHostSettings: Option[PlayerSettings] = Some(hostInfo)
  // List of clients who have joined the game
  val players: mutable.LinkedHashMap[String, PlayerWithActor] =
    mutable.LinkedHashMap[String, PlayerWithActor]()
  // List of clients who have established a ws connection, but not in lobby)
  val connected: mutable.LinkedHashMap[String, PlayerWithActor] =
    mutable.LinkedHashMap[String, PlayerWithActor]()
  // Always a member of players
  var host: Option[PlayerWithActor] = None
  // Current state of the game
  var state: State = GameLobbyState.Lobby
  var gameState: GameState = _

  def hasInitialHostJoined: Boolean = initialHostSettings.isEmpty
  def hasHost: Boolean = host.isEmpty

  def startGame() {
    gameState = new GameState(players.values.map(_.player).toList)
    // Send each player a list of players in their turn order with starting
    // armies
    val packet = UpdatePlayerState(gameState.playerStates)
    notifyGame(packet)
  }

  // Receive incoming packets and turn them into internal state
  // changes and/or outgoing packets (returns partial function)
  override def receive: Receive = {
    case p: GlobalPacket =>
      receiveGlobal(p)
    case p: LobbyPacket =>
      receiveLobby(p)
    case p: InGamePacket =>
      receiveInGame(p)
  }

  // Handle incoming packets in either state
  def receiveGlobal(globalPacket: GlobalPacket) {
    globalPacket match {
      case PlayerDisconnect(_, id: String) => clientDisconnect(id)
    }
  }

  // Handle incoming packets in the lobby state
  def receiveLobby(lobbyPacket: LobbyPacket) {
    lobbyPacket match {
      case PlayerConnect(_, id: String, actor: ActorRef) =>
        clientConnect(id, actor)
      case RequestPlayerJoin(_, id: String, settings: PlayerSettings) =>
        requestClientJoin(id, settings)
      case RequestStartGame(_, id: String) =>
        requestStartLobby(id)
      case p =>
        badPacket(p)
    }
  }

  // Handle incoming packets during the InGame state
  def receiveInGame(inGamePacket: InGamePacket) {
    inGamePacket match {
      case p =>
        badPacket(p)
    }
  }

  def clientConnect(clientId: String, actor: ActorRef) {
    if (!hasInitialHostJoined) {
      // Add the initial host to the list of players
      val client = PlayerWithActor(Player(clientId, initialHostSettings), actor)
      players += clientId -> client
      host = Some(client)
      initialHostSettings = None
      notifyGame(constructGameUpdate)
    } else {
      // Send current lobby information
      connected += clientId -> PlayerWithActor(Player(clientId), actor)
      actor ! constructGameUpdate
    }
  }

  def requestClientJoin(clientId: String, withSettings: PlayerSettings) {
    if (connected.isDefinedAt(clientId)) {
      if (!PlayerSettings.isValid(withSettings)) {
        // Reject with response
        connected(clientId).actor ! RequestReply(RequestResponse.Rejected,
          PlayerSettings.formatInvalid(withSettings))
      } else if (!isUnique(withSettings)) {
        // Reject with response
        connected(clientId).actor ! RequestReply(RequestResponse.Rejected,
          "Name and color must be unique: non-unique inputs " +
            s"{${nonUniqueElements(withSettings).mkString(", ")}}")
      } else {
        val client = connected(clientId)
        connected -= clientId
        players += clientId -> PlayerWithActor(Player(clientId, Some(withSettings)), client.actor)
        // Approve with response
        client.actor ! RequestReply(RequestResponse.Accepted)
        // Broadcast lobby update to all other players
        notifyGame(constructGameUpdate, Some(client.actor))
      }
    }
  }

  def requestStartLobby(clientId: String) {
    if (host.exists(_.player.id == clientId)) {
      if (players.size >= Resources.MinimumPlayers) {
        // Request is coming from the host, start game
        (players.values.iterator ++ connected.values.iterator)
          .foreach(_.actor ! StartLobby)
        connected.empty
        this.state = GameLobbyState.InGame
        startGame()

      } else {
        // Reject with response
        connected.getOrElse(clientId, players(clientId)).actor !
          RequestReply(RequestResponse.Rejected, "Cannot start game: " +
            s"not enough players (min: ${Resources.MinimumPlayers})")
      }

    } else {
      // Reject with response
      connected.getOrElse(clientId, players(clientId)).actor !
        RequestReply(RequestResponse.Rejected, "Must be the host " +
          "of the game lobby to start it (invalid privileges)")
    }
  }

  def clientDisconnect(clientId: String) {
    this.state match {
      case GameLobbyState.Lobby =>
        if (connected.isDefinedAt(clientId)) {
          // Player hadn't actually joined, silently remove them
          connected -= clientId
        } else if (host.exists(_.player.id == clientId)) {
          // Host disconnecting
          players.remove(clientId)
          // Promote the first-joined player to host if there is one
          host = if (players.isEmpty) None else Some(players.head._2)
          notifyGame(constructGameUpdate)
        } else {
          // Normal player disconnecting
          players.remove(clientId)
          notifyGame(constructGameUpdate)
        }

      case GameLobbyState.InGame =>
        // TODO Implement
    }
  }

  /**
    * Sends a message to all players connected to the game (connected
    * AND players)
    * @param exclude Optional player ActorRef to exclude sending the
    *                message to (used when accepting RequestClientJoins)
    */
  def notifyGame(packet: OutPacket, exclude: Option[ActorRef] = None) {
    (players.valuesIterator ++ connected.valuesIterator)
      .filter(exclude.isDefined && _.actor != exclude.get)
      .foreach(_.actor ! packet)
  }

  def constructGameUpdate: OutPacket =
    GameLobbyUpdate(players.valuesIterator
      .map(_.player.settings)
      .filter(_.isDefined)
      .map(_.get).toList,
      host.fold("")(_.player.settings.fold("")(_.name)))


  def packetInvalidState(p: InPacket) {
    connected.get(p.clientId).orElse(players.get(p.clientId)).foreach(
      actor => actor.actor ! BadPacket(s"Bad/unknown InPacket received: $p")
    )
  }

  def badPacket(p: InPacket) {
    connected.get(p.clientId).orElse(players.get(p.clientId)).foreach(
      actor => actor.actor ! BadPacket(s"Bad/unknown InPacket received: $p")
    )
  }
}
