package com.mchange.sc.v1.sbtethereum

import sbt._
import sbt.Keys._
import sbt.plugins.{JvmPlugin,InteractionServicePlugin}
import sbt.complete.Parser
import sbt.complete.DefaultParsers._
import sbt.InteractionServiceKeys.interactionService

import java.io.{BufferedInputStream,File,FileInputStream}

import play.api.libs.json.Json

import com.mchange.sc.v2.lang.borrow
import com.mchange.sc.v1.log.MLevel._

import com.mchange.sc.v1.consuela._
import com.mchange.sc.v1.consuela.ethereum._
import specification.Denominations

import com.mchange.sc.v1.consuela.ethereum.specification.Types.Unsigned256
import com.mchange.sc.v1.consuela.ethereum.specification.Fees.BigInt._

import scala.collection._

// XXX: provisionally, for now... but what sort of ExecutionContext would be best when?
import scala.concurrent.ExecutionContext.Implicits.global

object SbtEthereumPlugin extends AutoPlugin {

  // not lazy. make sure the initialization banner is emitted before any tasks are executed
  // still, generally log through sbt loggers
  private implicit val logger = mlogger( this ) 

  private val BufferSize = 4096

  private val SendGasAmount = G.transaction

  private val ContractNameParser = (Space.+ ~> ID)

  private val AddressParser = token(Space.* ~> literal("0x").? ~> Parser.repeat( HexDigit, 40, 40 ), "<recipient-address-hex>").map( chars => EthAddress.apply( chars.mkString ) )

  private val AmountParser = token(Space.* ~> Digit.+, "<amount>").map( chars => BigInt( chars.mkString ) )

  private val UnitParser = {
    val ( w, s, f, e ) = ( "wei", "szabo", "finney", "ether" );
    //(Space.* ~>(literal(w) | literal(s) | literal(f) | literal(e))).examples(w, s, f, e)
    Space.* ~> token(literal(w) | literal(s) | literal(f) | literal(e))
  }

  private val EthSendEtherParser : Parser[( EthAddress, BigInt )] = {
    def tupToTup( tup : ( ( EthAddress, BigInt ), String ) ) = ( tup._1._1, tup._1._2 * Denominations.Multiplier.BigInt( tup._2 ) )
    (AddressParser ~ AmountParser ~ UnitParser).map( tupToTup )
  }
  private val Zero256 = Unsigned256( 0 )

  private val ZeroEthAddress = (0 until 40).map(_ => "0").mkString("")

//  private val dynamicContractName = Def.inputTaskDyn {
//    val contractName = ContractNameParser.parsed
//    Def.taskDyn { Task( contractName ) }
//  }

  object autoImport {

    // settings

    val ethAddress = settingKey[String]("The address from which transactions will be sent")

    val ethGasOverrides = settingKey[Map[String,BigInt]]("Map of contract names to gas limits for contract creation transactions, overriding automatic estimates")

    val ethGasMarkup = settingKey[Double]("Fraction by which automatically estimated gas limits will be marked up (if not overridden) in setting contract creation transaction gas limits")

    val ethGasPrice = settingKey[BigInt]("If nonzero, use this gas price (in wei)) rather than the current blockchain default gas price.")

    val ethGethKeystore = settingKey[File]("geth-style keystore directory from which V3 wallets can be loaded")

    val ethJsonRpcVersion = settingKey[String]("Version of Ethereum's JSON-RPC spec the build should work with")

    val ethJsonRpcUrl = settingKey[String]("URL of the Ethereum JSON-RPC service build should work with")

    val ethTargetDir = settingKey[File]("Location in target directory where ethereum artifacts will be placed")

    val ethSoliditySource = settingKey[File]("Solidity source code directory")

    val ethSolidityDestination = settingKey[File]("Location for compiled solidity code and metadata")

    // tasks

    val ethCompileSolidity = taskKey[Unit]("Compiles solidity files")

    val ethDefaultGasPrice = taskKey[BigInt]("Finds the current default gas price")

    val ethDeployOnly = inputKey[EthHash]("Deploys the specified named contract")

    val ethGethWallet = taskKey[Option[wallet.V3]]("Loads a V3 wallet from a geth keystore")

    val ethGetCredential = taskKey[Option[String]]("Requests masked input of a credential (wallet passphrase or hex private key)")

    val ethLoadCompilations = taskKey[Map[String,jsonrpc20.Compilation.Contract]]("Loads compiled solidity contracts")

    val ethNextNonce = taskKey[BigInt]("Finds the next nonce for the address defined by setting 'ethAddress'")

    val ethSendEther = inputKey[EthHash]("Sends ether from ethAddress to a specified account, format 'ethSendEther <to-address-as-hex> <amount> <wei|szabo|finney|ether>'")

    // anonymous tasks

    val finalGasPrice = Def.task {
      val egp = ethGasPrice.value
      if ( egp > 0 ) egp else ethDefaultGasPrice.value
    }

    // definitions

    lazy val ethDefaults : Seq[sbt.Def.Setting[_]] = Seq(

      ethJsonRpcVersion := "2.0",
      ethJsonRpcUrl     := "http://localhost:8545",

      ethGasMarkup := 0.2,

      ethGasOverrides := Map.empty[String,BigInt],

      ethGasPrice := 0,

      ethGethKeystore := clients.geth.KeyStore.directory.get,

      ethAddress := ZeroEthAddress,

      ethTargetDir in Compile := (target in Compile).value / "ethereum",

      ethSoliditySource in Compile      := (sourceDirectory in Compile).value / "solidity",

      ethSolidityDestination in Compile := (ethTargetDir in Compile).value / "solidity",

      ethCompileSolidity in Compile := {
        val log            = streams.value.log
        val jsonRpcUrl     = ethJsonRpcUrl.value

        val solSource      = (ethSoliditySource in Compile).value
        val solDestination = (ethSolidityDestination in Compile).value

        doCompileSolidity( log, jsonRpcUrl, solSource, solDestination )
      },

      compile in Compile := {
        val dummy = (ethCompileSolidity in Compile).value
        (compile in Compile).value
      },

      ethDefaultGasPrice := {
        val log            = streams.value.log
        val jsonRpcUrl     = ethJsonRpcUrl.value
        doGetDefaultGasPrice( log, jsonRpcUrl )
      },

      ethNextNonce := {
        val log            = streams.value.log
        val jsonRpcUrl     = ethJsonRpcUrl.value
        doGetTransactionCount( log, jsonRpcUrl, EthAddress( ethAddress.value ), jsonrpc20.Client.BlockNumber.Pending )
      },

      ethLoadCompilations := {
        val dummy = (ethCompileSolidity in Compile).value // ensure compilation has completed

        val dir = (ethSolidityDestination in Compile).value

        def addContracts( addTo : immutable.Map[String,jsonrpc20.Compilation.Contract], name : String ) = {
          val next = borrow( new BufferedInputStream( new FileInputStream( new File( dir, name ) ), BufferSize ) )( Json.parse( _ ).as[immutable.Map[String,jsonrpc20.Compilation.Contract]] )
          addTo ++ next
        }

        dir.list.foldLeft( immutable.Map.empty[String,jsonrpc20.Compilation.Contract] )( addContracts )
      },

      ethGethWallet := {
        val log = streams.value.log
        val out = clients.geth.KeyStore.walletForAddress( ethGethKeystore.value, EthAddress( ethAddress.value ) ).toOption
        log.info( out.fold( s"V3 wallet not found for ${ethAddress.value}" )( _ => s"V3 wallet found for ${ethAddress.value}" ) )
        out
      },

      ethGetCredential := {
        interactionService.value.readLine(s"Enter passphrase or hex private key for address '${ethAddress.value}': ", mask = true)
      },

      ethDeployOnly := {
        val log = streams.value.log
        val jsonRpcUrl = ethJsonRpcUrl.value
        val contractName = ContractNameParser.parsed
        val contractsMap = ethLoadCompilations.value
        val hex = contractsMap( contractName ).code
        val nextNonce = ethNextNonce.value
        val gasPrice = finalGasPrice.value
        val gas = ethGasOverrides.value.getOrElse( contractName, doEstimateGas( log, jsonRpcUrl, EthAddress( ethAddress.value ), hex.decodeHex.toImmutableSeq, jsonrpc20.Client.BlockNumber.Pending ) )
        val unsigned = EthTransaction.Unsigned.ContractCreation( Unsigned256( nextNonce ), Unsigned256( gasPrice ), Unsigned256( gas ), Zero256, hex.decodeHex.toImmutableSeq )
        val privateKey = findPrivateKey( log, ethGethWallet.value, ethGetCredential.value.get )
        doSignSendTransaction( log, jsonRpcUrl, privateKey, unsigned )
      },

      ethSendEther := {
        val log = streams.value.log
        val jsonRpcUrl = ethJsonRpcUrl.value
        val args = EthSendEtherParser.parsed
        val to = args._1
        val amount = args._2
        val nextNonce = ethNextNonce.value
        val gasPrice = finalGasPrice.value
        val gas = SendGasAmount
        val unsigned = EthTransaction.Unsigned.Message( Unsigned256( nextNonce ), Unsigned256( gasPrice ), Unsigned256( gas ), to, Unsigned256( amount ), List.empty[Byte] )
        val privateKey = findPrivateKey( log, ethGethWallet.value, ethGetCredential.value.get )
        val out = doSignSendTransaction( log, jsonRpcUrl, privateKey, unsigned )
        log.info( s"Sent ${amount} wei to address '0x${to.hex}' in transaction '0x${out.hex}'." )
        out
      }
    )
  }


  import autoImport._

  // very important to ensure the ordering of settings,
  // so that compile actually gets overridden
  override def requires = JvmPlugin && InteractionServicePlugin

  override def trigger = allRequirements

  override val projectSettings = ethDefaults
}
