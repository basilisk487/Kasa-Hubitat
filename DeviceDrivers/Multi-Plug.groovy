/*	Kasa Device Driver Series
		Copyright Dave Gutheinz
License:  https://github.com/DaveGut/HubitatActive/blob/master/KasaDevices/License.md
===== Link to Documentation =====
	https://github.com/DaveGut/HubitatActive/tree/master/KasaDevices/Docs
Version 2.3.8a.
	NOTE:  Namespace Change.  At top of code for app and each driver.
	a.	Integrated common LOGGING Library into driver.
===================================================================================================*/
//	=====	NAMESPACE	============
def nameSpace() { return "davegut" }
//	================================

metadata {
	definition (name: "Kasa Multi Plug",
				namespace: nameSpace(),
				author: "Dave Gutheinz",
				importUrl: ""
			   ) {
		capability "Switch"
		capability "Actuator"
		capability "Configuration"
		capability "Refresh"
		command "ledOn"
		command "ledOff"
		attribute "led", "string"
		command "setPollInterval", [[
			name: "Poll Interval in seconds",
			constraints: ["default", "5 seconds", "10 seconds", "15 seconds",
						  "30 seconds", "1 minute", "5 minutes",  "10 minutes",
						  "30 minutes"],
			type: "ENUM"]]
		capability "Power Meter"
		capability "Energy Meter"
		attribute "currMonthTotal", "number"
		attribute "currMonthAvg", "number"
		attribute "lastMonthTotal", "number"
		attribute "lastMonthAvg", "number"
		attribute "connection", "string"
		attribute "commsError", "string"
	}
	preferences {
		input ("infoLog", "bool", 
			   title: "Enable descriptionText logging",
			   defaultValue: true)
		input ("logEnable", "bool",
			   title: "Enable debug logging",
			   defaultValue: false)
		if (getDataValue("feature") == "TIM:ENE") {
			input ("emFunction", "bool", 
				   title: "Enable Energy Monitor", 
				   defaultValue: false)
			if (emFunction) {
				input ("energyPollInt", "enum",
					   title: "Energy Poll Interval (minutes)",
					   options: ["1 minute", "5 minutes", "30 minutes"],
					   defaultValue: "30 minutes")
			}
		}
		if (getDataValue("deviceIP") != "CLOUD" && getDataValue("model") == "HS200") {
			input ("altLan", "bool",
			   	title: "Alternate LAN Comms (for comms problems only)",
			   	defaultValue: false)
		}
		input ("bind", "bool",
			   title: "Kasa Cloud Binding",
			   defalutValue: true)
		input ("useCloud", "bool",
		 	  title: "Use Kasa Cloud for device control",
		 	  defaultValue: false)
		input ("nameSync", "enum", title: "Synchronize Names",
			   options: ["none": "Don't synchronize",
						 "device" : "Kasa device name master", 
						 "Hubitat" : "Hubitat label master"],
			   defaultValue: "none")
		input ("manualIp", "string",
			   title: "Manual IP Update <b>[Caution]</b>",
			   defaultValue: getDataValue("deviceIP"))
		input ("manualPort", "string",
			   title: "Manual Port Update <b>[Caution]</b>",
			   defaultValue: getDataValue("devicePort"))
		input ("rebootDev", "bool",
			   title: "Reboot device <b>[Caution]</b>",
			   defaultValue: false)
	}
}

def installed() {
		device.updateSetting("nameSync",[type:"enum", value:"device"])
	def instStatus = installCommon()
	logInfo("installed: ${instStatus}")
}

def updated() {
	def updStatus = updateCommon()
	if (getDataValue("feature") == "TIM:ENE") {
		updStatus << [emFunction: setupEmFunction()]
	}
	logInfo("updated: ${updStatus}")
	
	if (getDataValue("model") == "HS300") {
		updateDataValue("altComms", "false")
		state.remove("response")
	}
	
	refresh()
}

def setSysInfo(status) {
	def switchStatus = status.relay_state
	def ledStatus = status.led_off
	def logData = [:]
	if (getDataValue("plugNo") != null) {
		def childStatus = status.children.find { it.id == getDataValue("plugNo") }
		if (childStatus == null) {
			childStatus = status.children.find { it.id == getDataValue("plugId") }
		}
		status = childStatus
		switchStatus = status.state
	}
	
	def onOff = "on"
	if (switchStatus == 0) { onOff = "off" }
	if (device.currentValue("switch") != onOff) {
		sendEvent(name: "switch", value: onOff, type: "digital")
		logData << [switch: onOff]
	}
	def ledOnOff = "on"
	if (ledStatus == 1) { ledOnOff = "off" }
	if (device.currentValue("led") != ledOnOff) {
		sendEvent(name: "led", value: ledOnOff)
		logData << [led: ledOnOff]
	}
	
	if (logData != [:]) {
		logInfo("setSysinfo: ${logData}")
	}
	if (nameSync == "device" || nameSync == "Hubitat") {
		updateName(status)
	}
	getPower()
}

def coordUpdate(cType, coordData) {
	def msg = "coordinateUpdate: "
	if (cType == "commsData") {
		device.updateSetting("bind", [type:"bool", value: coordData.bind])
		device.updateSetting("useCloud", [type:"bool", value: coordData.useCloud])
		sendEvent(name: "connection", value: coordData.connection)
		device.updateSetting("altLan", [type:"bool", value: coordData.altLan])
		msg += "[commsData: ${coordData}]"
	} else {
		msg += "Not updated."
	}
	logInfo(msg)
}







// ~~~~~ start include (70) davegut.kasaCommon ~~~~~
library (
	name: "kasaCommon",
	namespace: "davegut",
	author: "Dave Gutheinz",
	description: "Kasa Device Common Methods",
	category: "utilities",
	documentationLink: ""
)

def getVer() { return "" }

def installCommon() {
	def instStatus = [:]
	if (getDataValue("deviceIP") == "CLOUD") {
		sendEvent(name: "connection", value: "CLOUD")
		device.updateSetting("useCloud", [type:"bool", value: true])
		instStatus << [useCloud: true, connection: "CLOUD"]
	} else {
		sendEvent(name: "connection", value: "LAN")
		device.updateSetting("useCloud", [type:"bool", value: false])
		instStatus << [useCloud: false, connection: "LAN"]
	}

	sendEvent(name: "commsError", value: "false")
	state.errorCount = 0
	state.pollInterval = "30 minutes"
	runIn(5, updated)
	return instStatus
}

def updateCommon() {
	def updStatus = [:]
	if (rebootDev) {
		updStatus << [rebootDev: rebootDevice()]
		return updStatus
	}
	unschedule()
	updStatus << [bind: bindUnbind()]
	if (nameSync != "none") {
		updStatus << [nameSync: syncName()]
	}
	if (logEnable) { runIn(1800, debugLogOff) }
	updStatus << [infoLog: infoLog, logEnable: logEnable]
	if (manualIp != getDataValue("deviceIP")) {
		updateDataValue("deviceIP", manualIp)
		updStatus << [ipUpdate: manualIp]
	}
	if (manualPort != getDataValue("devicePort")) {
		updateDataValue("devicePort", manualPort)
		updStatus << [portUpdate: manualPort]
	}
	state.errorCount = 0
	sendEvent(name: "commsError", value: "false")

	def pollInterval = state.pollInterval
	if (pollInterval == null) { pollInterval = "30 minutes" }
	state.pollInterval = pollInterval
	runIn(15, setPollInterval)
	updStatus << [pollInterval: pollInterval]
	if (emFunction) {
		scheduleEnergyAttrs()
		state.getEnergy = "This Month"
		updStatus << [emFunction: "scheduled"]
	}
	return updStatus
}

def configure() {
	Map logData = [method: "configure"]
	logInfo logData
	if (parent == null) {
		logData << [error: "No Parent App.  Aborted"]
		logWarn(logData)
	} else {
		logData << [appData: parent.updateConfigurations()]
		logInfo(logData)
	}
}

def refresh() { poll() }

def poll() { getSysinfo() }

def setPollInterval(interval = state.pollInterval) {
	if (interval == "default" || interval == "off" || interval == null) {
		interval = "30 minutes"
	} else if (useCloud || altLan || getDataValue("altComms") == "true") {
		if (interval.contains("sec")) {
			interval = "1 minute"
			logWarn("setPollInterval: Device using Cloud or rawSocket.  Poll interval reset to minimum value of 1 minute.")
		}
	}
	state.pollInterval = interval
	def pollInterval = interval.substring(0,2).toInteger()
	if (interval.contains("sec")) {
		def start = Math.round((pollInterval-1) * Math.random()).toInteger()
		schedule("${start}/${pollInterval} * * * * ?", "poll")
		logWarn("setPollInterval: Polling intervals of less than one minute " +
				"can take high resources and may impact hub performance.")
	} else {
		def start = Math.round(59 * Math.random()).toInteger()
		schedule("${start} */${pollInterval} * * * ?", "poll")
	}
	logDebug("setPollInterval: interval = ${interval}.")
	return interval
}

def rebootDevice() {
	device.updateSetting("rebootDev", [type:"bool", value: false])
	reboot()
	pauseExecution(10000)
	return "REBOOTING DEVICE"
}

def bindUnbind() {
	def message
	if (getDataValue("deviceIP") == "CLOUD") {
		device.updateSetting("bind", [type:"bool", value: true])
		device.updateSetting("useCloud", [type:"bool", value: true])
		message = "No deviceIp.  Bind not modified."
	} else if (bind == null ||  getDataValue("feature") == "lightStrip") {
		message = "Getting current bind state"
		getBind()
	} else if (bind == true) {
		if (!parent.kasaToken || parent.userName == null || parent.userPassword == null) {
			message = "Username/pwd not set."
			getBind()
		} else {
			message = "Binding device to the Kasa Cloud."
			setBind(parent.userName, parent.userPassword)
		}
	} else if (bind == false) {
		message = "Unbinding device from the Kasa Cloud."
		setUnbind()
	}
	pauseExecution(5000)
	return message
}

def setBindUnbind(cmdResp) {
	def bindState = true
	if (cmdResp.get_info) {
		if (cmdResp.get_info.binded == 0) { bindState = false }
		logInfo("setBindUnbind: Bind status set to ${bindState}")
		setCommsType(bindState)
	} else if (cmdResp.bind.err_code == 0){
		getBind()
	} else {
		logWarn("setBindUnbind: Unhandled response: ${cmdResp}")
	}
}

def setCommsType(bindState) {
	def commsType = "LAN"
	def cloudCtrl = false
	if (bindState == false && useCloud == true) {
		logWarn("setCommsType: Can not use cloud.  Device is not bound to Kasa cloud.")
	} else if (bindState == true && useCloud == true && parent.kasaToken) {
		commsType = "CLOUD"
		cloudCtrl = true
	} else if (altLan == true) {
		commsType = "AltLAN"
		state.response = ""
	}
	def commsSettings = [bind: bindState, useCloud: cloudCtrl, commsType: commsType]
	device.updateSetting("bind", [type:"bool", value: bindState])
	device.updateSetting("useCloud", [type:"bool", value: cloudCtrl])
	sendEvent(name: "connection", value: "${commsType}")
	logInfo("setCommsType: ${commsSettings}")
	if (getDataValue("plugNo") != null) {
		def coordData = [:]
		coordData << [bind: bindState]
		coordData << [useCloud: cloudCtrl]
		coordData << [connection: commsType]
		coordData << [altLan: altLan]
		parent.coordinate("commsData", coordData, getDataValue("deviceId"), getDataValue("plugNo"))
	}
	pauseExecution(1000)
}

def syncName() {
	def message
	if (nameSync == "Hubitat") {
		message = "Hubitat Label Sync"
		setDeviceAlias(device.getLabel())
	} else if (nameSync == "device") {
		message = "Device Alias Sync"
	} else {
		message = "Not Syncing"
	}
	device.updateSetting("nameSync",[type:"enum", value:"none"])
	return message
}

def updateName(response) {
	def name = device.getLabel()
	if (response.alias) {
		name = response.alias
		device.setLabel(name)
	} else if (response.err_code != 0) {
		def msg = "updateName: Name Sync from Hubitat to Device returned an error."
		msg+= "\n\rNote: <b>Some devices do not support syncing name from the hub.</b>\n\r"
		logWarn(msg)
		return
	}
	logInfo("updateName: Hubitat and Kasa device name synchronized to ${name}")
}

def getSysinfo() {
	if (getDataValue("altComms") == "true") {
		sendTcpCmd("""{"system":{"get_sysinfo":{}}}""")
	} else {
		sendCmd("""{"system":{"get_sysinfo":{}}}""")
	}
}

def bindService() {
	def service = "cnCloud"
	def feature = getDataValue("feature")
	if (feature.contains("Bulb") || feature == "lightStrip") {
		service = "smartlife.iot.common.cloud"
	}
	return service
}

def getBind() {
	if (getDataValue("deviceIP") == "CLOUD") {
		logDebug("getBind: [status: notRun, reason: [deviceIP: CLOUD]]")
	} else {
		sendLanCmd("""{"${bindService()}":{"get_info":{}}}""")
	}
}

def setBind(userName, password) {
	if (getDataValue("deviceIP") == "CLOUD") {
		logDebug("setBind: [status: notRun, reason: [deviceIP: CLOUD]]")
	} else {
		sendLanCmd("""{"${bindService()}":{"bind":{"username":"${userName}",""" +
				   """"password":"${password}"}},""" +
				   """"${bindService()}":{"get_info":{}}}""")
	}
}

def setUnbind() {
	if (getDataValue("deviceIP") == "CLOUD") {
		logDebug("setUnbind: [status: notRun, reason: [deviceIP: CLOUD]]")
	} else {
		sendLanCmd("""{"${bindService()}":{"unbind":""},""" +
				   """"${bindService()}":{"get_info":{}}}""")
	}
}

def sysService() {
	def service = "system"
	def feature = getDataValue("feature")
	if (feature.contains("Bulb") || feature == "lightStrip") {
		service = "smartlife.iot.common.system"
	}
	return service
}

def reboot() {
	sendCmd("""{"${sysService()}":{"reboot":{"delay":1}}}""")
}

def setDeviceAlias(newAlias) {
	if (getDataValue("plugNo") != null) {
		sendCmd("""{"context":{"child_ids":["${getDataValue("plugId")}"]},""" +
				""""system":{"set_dev_alias":{"alias":"${device.getLabel()}"}}}""")
	} else {
		sendCmd("""{"${sysService()}":{"set_dev_alias":{"alias":"${device.getLabel()}"}}}""")
	}
}

def updateAttr(attr, value) {
	if (device.currentValue(attr) != value) {
		sendEvent(name: attr, value: value)
	}
}


// ~~~~~ end include (70) davegut.kasaCommon ~~~~~

// ~~~~~ start include (71) davegut.kasaCommunications ~~~~~
library (
	name: "kasaCommunications",
	namespace: "davegut",
	author: "Dave Gutheinz",
	description: "Kasa Communications Methods",
	category: "communications",
	documentationLink: ""
)

import groovy.json.JsonSlurper
import org.json.JSONObject

def getPort() {
	def port = 9999
	if (getDataValue("devicePort")) {
		port = getDataValue("devicePort")
	}
	return port
}

def sendCmd(command) {
	state.lastCommand = command
	def connection = device.currentValue("connection")
	if (connection == "LAN") {
		sendLanCmd(command)
	} else if (connection == "CLOUD") {
		sendKasaCmd(command)
	} else if (connection == "AltLAN") {
		sendTcpCmd(command)
	} else {
		logWarn("sendCmd: attribute connection is not set.")
	}
}

def sendLanCmd(command) {
	logDebug("sendLanCmd: [ip: ${getDataValue("deviceIP")}, cmd: ${command}]")
	def myHubAction = new hubitat.device.HubAction(
		outputXOR(command),
		hubitat.device.Protocol.LAN,
		[type: hubitat.device.HubAction.Type.LAN_TYPE_UDPCLIENT,
		 destinationAddress: "${getDataValue("deviceIP")}:${getPort()}",
		 encoding: hubitat.device.HubAction.Encoding.HEX_STRING,
		 parseWarning: true,
		 timeout: 8,
		 ignoreResponse: false,
		 callback: "parseUdp"])
	try {
		sendHubCommand(myHubAction)
		if (state.errorCount > 0 && state.errorCount < 4) {
			runIn(9, handleCommsError, [overwrite: false])
		}
		state.errorCount += 1
	} catch (e) {
		logWarn("sendLanCmd: [ip: ${getDataValue("deviceIP")}, error: ${e}]")
		handleCommsError()
	}
}
def parseUdp(message) {
	def resp = parseLanMessage(message)
	if (resp.type == "LAN_TYPE_UDPCLIENT") {
		def clearResp = inputXOR(resp.payload)
		if (clearResp.length() > 1023) {
			if (clearResp.contains("preferred")) {
				clearResp = clearResp.substring(0,clearResp.indexOf("preferred")-2) + "}}}"
			} else if (clearResp.contains("child_num")) {
				clearResp = clearResp.substring(0,clearResp.indexOf("child_num") -2) + "}}}"
			} else {
				logWarn("parseUdp: [status: converting to altComms, error: udp msg can not be parsed]")
				logDebug("parseUdp: [messageData: ${clearResp}]")
				updateDataValue("altComms", "true")
				state.errorCount = 0
				sendTcpCmd(state.lastCommand)
				return
			}
		}
		def cmdResp = new JsonSlurper().parseText(clearResp)
		logDebug("parseUdp: ${cmdResp}")
		state.errorCount = 0
		distResp(cmdResp)
	} else {
		logWarn("parseUdp: [error: error, reason: not LAN_TYPE_UDPCLIENT, respType: ${resp.type}]")
		handleCommsError()
	}
}

def sendKasaCmd(command) {
	logDebug("sendKasaCmd: ${command}")
	def cmdResponse = ""
	def cmdBody = [
		method: "passthrough",
		params: [
			deviceId: getDataValue("deviceId"),
			requestData: "${command}"
		]
	]
	if (!parent.kasaCloudUrl || !parent.kasaToken) {
		logWarn("sendKasaCmd: Cloud interface not properly set up.")
		return
	}
	def sendCloudCmdParams = [
		uri: "${parent.kasaCloudUrl}/?token=${parent.kasaToken}",
		requestContentType: 'application/json',
		contentType: 'application/json',
		headers: ['Accept':'application/json; version=1, */*; q=0.01'],
		timeout: 10,
		body : new groovy.json.JsonBuilder(cmdBody).toString()
	]
	try {
		asynchttpPost("cloudParse", sendCloudCmdParams)
	} catch (e) {
		def msg = "sendKasaCmd: <b>Error in Cloud Communications.</b> The Kasa Cloud is unreachable."
		msg += "\nAdditional Data: Error = ${e}\n\n"
		logWarn(msg)
	}
}
def cloudParse(resp, data = null) {
	try {
		response = new JsonSlurper().parseText(resp.data)
	} catch (e) {
		response = [error_code: 9999, data: e]
	}
	if (resp.status == 200 && response.error_code == 0 && resp != []) {
		def cmdResp = new JsonSlurper().parseText(response.result.responseData)
		logDebug("cloudParse: ${cmdResp}")
		distResp(cmdResp)
	} else {
		def msg = "cloudParse:\n<b>Error from the Kasa Cloud.</b> Most common cause is "
		msg += "your Kasa Token has expired.  Run Kasa Login and Token update and try again."
		msg += "\nAdditional Data: Error = ${resp.data}\n\n"
		logDebug(msg)
	}
}

def sendTcpCmd(command) {
	logDebug("sendTcpCmd: ${command}")
	try {
		interfaces.rawSocket.connect("${getDataValue("deviceIP")}",
									 getPort().toInteger(), byteInterface: true)
	} catch (error) {
		logDebug("SendTcpCmd: [connectFailed: [ip: ${getDataValue("deviceIP")}, Error = ${error}]]")
	}
	state.response = ""
	interfaces.rawSocket.sendMessage(outputXorTcp(command))
}
def close() { interfaces.rawSocket.close() }
def socketStatus(message) {
	if (message != "receive error: Stream closed.") {
		logDebug("socketStatus: Socket Established")
	} else {
		logWarn("socketStatus = ${message}")
	}
}
def parse(message) {
	if (message != null || message != "") {
		def response = state.response.concat(message)
		state.response = response
		extractTcpResp(response)
	}
}
def extractTcpResp(response) {
	def cmdResp
	def clearResp = inputXorTcp(response)
	if (clearResp.endsWith("}}}")) {
		interfaces.rawSocket.close()
		try {
			cmdResp = parseJson(clearResp)
			distResp(cmdResp)
		} catch (e) {
			logWarn("extractTcpResp: [length: ${clearResp.length()}, clearResp: ${clearResp}, comms error: ${e}]")
		}
	} else if (clearResp.length() > 2000) {
		interfaces.rawSocket.close()
	}
}

def handleCommsError() {
	Map logData = [method: "handleCommsError"]
	if (state.errorCount > 0 && state.lastCommand != "") {
		unschedule("poll")
		runIn(60, setPollInterval)
		logData << [count: state.errorCount, command: state.lastCommand]
		switch (state.errorCount) {
			case 1:
			case 2:
			case 3:
				if (getDataValue("altComms") == "true") {
					sendTcpCmd(state.lastCommand)
				} else {
					sendCmd(state.lastCommand)
				}
				logDebug(logData)
				break
			case 4:
				updateAttr("commsError", "true")
				logData << [setCommsError: true, status: "retriesDisabled"]
				logData << [TRY: "<b>  CONFIGURE</b>"]
				logData << [commonERROR: "IP Address not static in Router"]
				logWarn(logData)
				break
			default:
				logData << [TRY: "<b>do CONFIGURE</b>"]
				logData << [commonERROR: "IP Address not static in Router"]
				logWarn(logData)
				break
		}
	}
}

private outputXOR(command) {
	def str = ""
	def encrCmd = ""
 	def key = 0xAB
	for (int i = 0; i < command.length(); i++) {
		str = (command.charAt(i) as byte) ^ key
		key = str
		encrCmd += Integer.toHexString(str)
	}
   	return encrCmd
}

private inputXOR(encrResponse) {
	String[] strBytes = encrResponse.split("(?<=\\G.{2})")
	def cmdResponse = ""
	def key = 0xAB
	def nextKey
	byte[] XORtemp
	for(int i = 0; i < strBytes.length; i++) {
		nextKey = (byte)Integer.parseInt(strBytes[i], 16)	// could be negative
		XORtemp = nextKey ^ key
		key = nextKey
		cmdResponse += new String(XORtemp)
	}
	return cmdResponse
}

private outputXorTcp(command) {
	def str = ""
	def encrCmd = "000000" + Integer.toHexString(command.length()) 
 	def key = 0xAB
	for (int i = 0; i < command.length(); i++) {
		str = (command.charAt(i) as byte) ^ key
		key = str
		encrCmd += Integer.toHexString(str)
	}
   	return encrCmd
}

private inputXorTcp(resp) {
	String[] strBytes = resp.substring(8).split("(?<=\\G.{2})")
	def cmdResponse = ""
	def key = 0xAB
	def nextKey
	byte[] XORtemp
	for(int i = 0; i < strBytes.length; i++) {
		nextKey = (byte)Integer.parseInt(strBytes[i], 16)	// could be negative
		XORtemp = nextKey ^ key
		key = nextKey
		cmdResponse += new String(XORtemp)
	}
	return cmdResponse
}

// ~~~~~ end include (71) davegut.kasaCommunications ~~~~~

// ~~~~~ start include (67) davegut.Logging ~~~~~
library (
	name: "Logging",
	namespace: "davegut",
	author: "Dave Gutheinz",
	description: "Common Logging and info gathering Methods",
	category: "utilities",
	documentationLink: ""
)

def label() {
	if (device) { return device.displayName } 
	else { return app.getLabel() }
}

def listAttributes() {
	def attrData = device.getCurrentStates()
	Map attrs = [:]
	attrData.each {
		attrs << ["${it.name}": it.value]
	}
	return attrs
}

def setLogsOff() {
	def logData = [logEnable: logEnable]
	if (logEnable) {
		runIn(1800, debugLogOff)
		logData << [debugLogOff: "scheduled"]
	}
	return logData
}

def logTrace(msg){ log.trace "${label()} ${getVer()}: ${msg}" }

def logInfo(msg) { 
	if (infoLog) { log.info "${label()} ${getVer()}: ${msg}" }
}

def debugLogOff() {
	if (device) {
		device.updateSetting("logEnable", [type:"bool", value: false])
	} else {
		app.updateSetting("logEnable", false)
	}
	logInfo("debugLogOff")
}

def logDebug(msg) {
	if (logEnable) { log.debug "${label()} ${getVer()}: ${msg}" }
}

def logWarn(msg) { log.warn "${label()} ${getVer()}: ${msg}" }

def logError(msg) { log.error "${label()} ${getVer()}}: ${msg}" }

// ~~~~~ end include (67) davegut.Logging ~~~~~

// ~~~~~ start include (74) davegut.kasaPlugs ~~~~~
library (
	name: "kasaPlugs",
	namespace: "davegut",
	author: "Dave Gutheinz",
	description: "Kasa Plug and Switches Common Methods",
	category: "utilities",
	documentationLink: ""
)

def on() { setRelayState(1) }

def off() { setRelayState(0) }

def ledOn() { setLedOff(0) }

def ledOff() { setLedOff(1) }

def distResp(response) {
	if (response.system) {
		if (response.system.get_sysinfo) {
			setSysInfo(response.system.get_sysinfo)
		} else if (response.system.set_relay_state ||
				   response.system.set_led_off) {
			if (getDataValue("model") == "HS210") {
				runIn(2, getSysinfo)
			} else {
				getSysinfo()
			}
		} else if (response.system.reboot) {
			logWarn("distResp: Rebooting device.")
		} else if (response.system.set_dev_alias) {
			updateName(response.system.set_dev_alias)
		} else {
			logDebug("distResp: Unhandled response = ${response}")
		}
	} else if (response["smartlife.iot.dimmer"]) {
		if (response["smartlife.iot.dimmer"].get_dimmer_parameters) {
			setDimmerConfig(response["smartlife.iot.dimmer"])
		} else {
			logDebug("distResp: Unhandled response: ${response["smartlife.iot.dimmer"]}")
		}
	} else if (response.emeter) {
		distEmeter(response.emeter)
	} else if (response.cnCloud) {
		setBindUnbind(response.cnCloud)
	} else {
		logDebug("distResp: Unhandled response = ${response}")
	}
}

def setRelayState(onOff) {
	logDebug("setRelayState: [switch: ${onOff}]")
	if (getDataValue("plugNo") == null) {
		sendCmd("""{"system":{"set_relay_state":{"state":${onOff}}}}""")
	} else {
		sendCmd("""{"context":{"child_ids":["${getDataValue("plugId")}"]},""" +
				""""system":{"set_relay_state":{"state":${onOff}}}}""")
	}
}

def setLedOff(onOff) {
	logDebug("setLedOff: [ledOff: ${onOff}]")
		sendCmd("""{"system":{"set_led_off":{"off":${onOff}}}}""")
}

// ~~~~~ end include (74) davegut.kasaPlugs ~~~~~

// ~~~~~ start include (72) davegut.kasaEnergyMonitor ~~~~~
library (
	name: "kasaEnergyMonitor",
	namespace: "davegut",
	author: "Dave Gutheinz",
	description: "Kasa Device Energy Monitor Methods",
	category: "energyMonitor",
	documentationLink: ""
)

def setupEmFunction() {
	if (emFunction && device.currentValue("currMonthTotal") > 0) {
		state.getEnergy = "This Month"
		return "Continuing EM Function"
	} else if (emFunction) {
		zeroizeEnergyAttrs()
		state.response = ""
		state.getEnergy = "This Month"
		//	Run order / delay is critical for successful operation.
		getEnergyThisMonth()
		runIn(10, getEnergyLastMonth)
		return "Initialized"
	} else if (emFunction && device.currentValue("power") != null) {
		//	for power != null, EM had to be enabled at one time.  Set values to 0.
		zeroizeEnergyAttrs()
		state.remove("getEnergy")
		return "Disabled"
	} else {
		return "Not initialized"
	}
}

def scheduleEnergyAttrs() {
	schedule("10 0 0 * * ?", getEnergyThisMonth)
	schedule("15 2 0 1 * ?", getEnergyLastMonth)
	switch(energyPollInt) {
		case "1 minute":
			runEvery1Minute(getEnergyToday)
			break
		case "5 minutes":
			runEvery5Minutes(getEnergyToday)
			break
		default:
			runEvery30Minutes(getEnergyToday)
	}
}

def zeroizeEnergyAttrs() {
	sendEvent(name: "power", value: 0, unit: "W")
	sendEvent(name: "energy", value: 0, unit: "KWH")
	sendEvent(name: "currMonthTotal", value: 0, unit: "KWH")
	sendEvent(name: "currMonthAvg", value: 0, unit: "KWH")
	sendEvent(name: "lastMonthTotal", value: 0, unit: "KWH")
	sendEvent(name: "lastMonthAvg", value: 0, unit: "KWH")
}

def getDate() {
	def currDate = new Date()
	int year = currDate.format("yyyy").toInteger()
	int month = currDate.format("M").toInteger()
	int day = currDate.format("d").toInteger()
	return [year: year, month: month, day: day]
}

def distEmeter(emeterResp) {
	def date = getDate()
	logDebug("distEmeter: ${emeterResp}, ${date}, ${state.getEnergy}")
	def lastYear = date.year - 1
	if (emeterResp.get_realtime) {
		setPower(emeterResp.get_realtime)
	} else if (emeterResp.get_monthstat) {
		def monthList = emeterResp.get_monthstat.month_list
		if (state.getEnergy == "Today") {
			setEnergyToday(monthList, date)
		} else if (state.getEnergy == "This Month") {
			setThisMonth(monthList, date)
		} else if (state.getEnergy == "Last Month") {
			setLastMonth(monthList, date)
		} else if (monthList == []) {
			logDebug("distEmeter: monthList Empty. No data for year.")
		}
	} else {
		logWarn("distEmeter: Unhandled response = ${emeterResp}")
	}
}

def getPower() {
	if (emFunction) {
		if (device.currentValue("switch") == "on") {
			getRealtime()
		} else if (device.currentValue("power") != 0) {
			sendEvent(name: "power", value: 0, descriptionText: "Watts", unit: "W", type: "digital")
		}
	}
}

def setPower(response) {
	logDebug("setPower: ${response}")
	def power = response.power
	if (power == null) { power = response.power_mw / 1000 }
	power = (power + 0.5).toInteger()
	def curPwr = device.currentValue("power")
	def pwrChange = false
	if (curPwr != power) {
		if (curPwr == null || (curPwr == 0 && power > 0)) {
			pwrChange = true
		} else {
			def changeRatio = Math.abs((power - curPwr) / curPwr)
			if (changeRatio > 0.03) {
				pwrChange = true
			}
		}
	}
	if (pwrChange == true) {
		sendEvent(name: "power", value: power, descriptionText: "Watts", unit: "W", type: "digital")
	}
}

def getEnergyToday() {
	if (device.currentValue("switch") == "on") {
		state.getEnergy = "Today"
		def year = getDate().year
		logDebug("getEnergyToday: ${year}")
		runIn(5, getMonthstat, [data: year])
	}
}

def setEnergyToday(monthList, date) {
	logDebug("setEnergyToday: ${date}, ${monthList}")
	def data = monthList.find { it.month == date.month && it.year == date.year}
	def status = [:]
	def energy = 0
	if (data == null) {
		status << [msgError: "Return Data Null"]
	} else {
		energy = data.energy
		if (energy == null) { energy = data.energy_wh/1000 }
		energy = Math.round(100*energy)/100 - device.currentValue("currMonthTotal")
	}
	if (device.currentValue("energy") != energy) {
		sendEvent(name: "energy", value: energy, descriptionText: "KiloWatt Hours", unit: "KWH")
		status << [energy: energy]
	}
	if (status != [:]) { logInfo("setEnergyToday: ${status}") }
	if (!state.getEnergy) {
		schedule("10 0 0 * * ?", getEnergyThisMonth)
		schedule("15 2 0 1 * ?", getEnergyLastMonth)
		state.getEnergy = "This Month"
		getEnergyThisMonth()
		runIn(10, getEnergyLastMonth)
	}
}

def getEnergyThisMonth() {
	state.getEnergy = "This Month"
	def year = getDate().year
	logDebug("getEnergyThisMonth: ${year}")
	runIn(5, getMonthstat, [data: year])
}

def setThisMonth(monthList, date) {
	logDebug("setThisMonth: ${date} // ${monthList}")
	def data = monthList.find { it.month == date.month && it.year == date.year}
	def status = [:]
	def totEnergy = 0
	def avgEnergy = 0
	if (data == null) {
		status << [msgError: "Return Data Null"]
	} else {
		status << [msgError: "OK"]
		totEnergy = data.energy
		if (totEnergy == null) { totEnergy = data.energy_wh/1000 }
		if (date.day == 1) {
			avgEnergy = 0
		} else {
			avgEnergy = totEnergy /(date.day - 1)
		}
	}
	totEnergy = Math.round(100*totEnergy)/100
	avgEnergy = Math.round(100*avgEnergy)/100
	sendEvent(name: "currMonthTotal", value: totEnergy, 
			  descriptionText: "KiloWatt Hours", unit: "KWH")
	status << [currMonthTotal: totEnergy]
	sendEvent(name: "currMonthAvg", value: avgEnergy, 
		 	 descriptionText: "KiloWatt Hours per Day", unit: "KWH")
	status << [currMonthAvg: avgEnergy]
	getEnergyToday()
	logInfo("setThisMonth: ${status}")
}

def getEnergyLastMonth() {
	state.getEnergy = "Last Month"
	def date = getDate()
	def year = date.year
	if (date.month == 1) {
		year = year - 1
	}
	logDebug("getEnergyLastMonth: ${year}")
	runIn(5, getMonthstat, [data: year])
}

def setLastMonth(monthList, date) {
	logDebug("setLastMonth: ${date} // ${monthList}")
	def lastMonthYear = date.year
	def lastMonth = date.month - 1
	if (date.month == 1) {
		lastMonthYear -+ 1
		lastMonth = 12
	}
	def data = monthList.find { it.month == lastMonth }
	def status = [:]
	def totEnergy = 0
	def avgEnergy = 0
	if (data == null) {
		status << [msgError: "Return Data Null"]
	} else {
		status << [msgError: "OK"]
		def monthLength
		switch(lastMonth) {
			case 4:
			case 6:
			case 9:
			case 11:
				monthLength = 30
				break
			case 2:
				monthLength = 28
				if (lastMonthYear == 2020 || lastMonthYear == 2024 || lastMonthYear == 2028) { 
					monthLength = 29
				}
				break
			default:
				monthLength = 31
		}
		totEnergy = data.energy
		if (totEnergy == null) { totEnergy = data.energy_wh/1000 }
		avgEnergy = totEnergy / monthLength
	}
	totEnergy = Math.round(100*totEnergy)/100
	avgEnergy = Math.round(100*avgEnergy)/100
	sendEvent(name: "lastMonthTotal", value: totEnergy, 
			  descriptionText: "KiloWatt Hours", unit: "KWH")
	status << [lastMonthTotal: totEnergy]
	sendEvent(name: "lastMonthAvg", value: avgEnergy, 
			  descriptionText: "KiloWatt Hoursper Day", unit: "KWH")
	status << [lastMonthAvg: avgEnergy]
	logInfo("setLastMonth: ${status}")
}

def getRealtime() {
	def feature = getDataValue("feature")
	if (getDataValue("plugNo") != null) {
		sendCmd("""{"context":{"child_ids":["${getDataValue("plugId")}"]},""" +
				""""emeter":{"get_realtime":{}}}""")
	} else if (feature.contains("Bulb") || feature == "lightStrip") {
		sendCmd("""{"smartlife.iot.common.emeter":{"get_realtime":{}}}""")
	} else {
		sendCmd("""{"emeter":{"get_realtime":{}}}""")
	}
}

def getMonthstat(year) {
	def feature = getDataValue("feature")
	if (getDataValue("plugNo") != null) {
		sendCmd("""{"context":{"child_ids":["${getDataValue("plugId")}"]},""" +
				""""emeter":{"get_monthstat":{"year": ${year}}}}""")
	} else if (feature.contains("Bulb") || feature == "lightStrip") {
		sendCmd("""{"smartlife.iot.common.emeter":{"get_monthstat":{"year": ${year}}}}""")
	} else {
		sendCmd("""{"emeter":{"get_monthstat":{"year": ${year}}}}""")
	}
}

// ~~~~~ end include (72) davegut.kasaEnergyMonitor ~~~~~
