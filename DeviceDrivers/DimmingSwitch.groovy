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
	definition (name: "Kasa Dimming Switch",
				namespace: nameSpace(),
				author: "Dave Gutheinz",
				importUrl: ""
			   ) {
		capability "Switch"
		capability "Actuator"
		capability "Refresh"
		capability "Configuration"
		capability "Switch Level"
		capability "Level Preset"
		capability "Change Level"
		command "ledOn"
		command "ledOff"
		attribute "led", "string"
		command "setPollInterval", [[
			name: "Poll Interval in seconds",
			constraints: ["default", "5 seconds", "10 seconds", "15 seconds",
						  "30 seconds", "1 minute", "5 minutes",  "10 minutes",
						  "30 minutes"],
			type: "ENUM"]]
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
		input ("gentleOn", "number",
			   title: "Gentle On (max 7000 msec)",
			   defaultValue:5000,
			   range: 0 .. 7100)
		input ("gentleOff", "number",
			   title: "Gentle Off (max 7000 msec)",
			   defaultValue:5000,
			   range: 0 .. 7100)
		def fadeOpts = [0: "Instant",  1000: "Fast",
						2000: "Medium", 3000: "Slow"]
		input ("fadeOn", "enum",
			   title: "Fade On",
			   defaultValue:"Fast",
			   options: fadeOpts)
		input ("fadeOff", "enum",
			   title: "Fade Off",
			   defaultValue:"Fast",
			   options: fadeOpts)
		def pressOpts = ["none",  "instant_on_off", "gentle_on_off",
						 "Preset 0", "Preset 1", "Preset 2", "Preset 3"]
		input ("longPress", "enum", title: "Long Press Action",
			   defaultValue: "gentle_on_off",
			   options: pressOpts)
		input ("doubleClick", "enum", title: "Double Tap Action",
			   defaultValue: "Preset 1",
			   options: pressOpts)
		input ("bind", "bool",
			   title: "Kasa Cloud Binding",
			   defalutValue: true)
		input ("useCloud", "bool",
		 	  title: "Use Kasa Cloud for device control",
		 	  defaultValue: false)
		input ("nameSync", "enum", title: "Synchronize Names",
			   defaultValue: "none",
			   options: ["none": "Don't synchronize",
						 "device" : "Kasa device name master", 
						 "Hubitat" : "Hubitat label master"])
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
	def instStatus = installCommon()
	runIn(1, getDimmerConfiguration)
	logInfo("installed: ${instStatus}")
}

def updated() {
	def updStatus = updateCommon()
	configureDimmer()
	logInfo("updated: ${updStatus}")
	refresh()
}

def configureDimmer() {
	logDebug("configureDimmer")
	if (longPress == null || doubleClick == null || gentleOn == null
	    || gentleOff == null || fadeOff == null || fadeOn == null) {
		def dimmerSet = getDimmerConfiguration()
		pauseExecution(2000)
	}
	sendCmd("""{"smartlife.iot.dimmer":{"set_gentle_on_time":{"duration": ${gentleOn}}, """ +
			""""set_gentle_off_time":{"duration": ${gentleOff}}, """ +
			""""set_fade_on_time":{"fadeTime": ${fadeOn}}, """ +
			""""set_fade_off_time":{"fadeTime": ${fadeOff}}}}""")
	pauseExecution(2000)

	def action1 = """{"mode":"${longPress}"}"""
	if (longPress.contains("Preset")) {
		action1 = """{"mode":"customize_preset","index":${longPress[-1].toInteger()}}"""
	}
	def action2 = """{"mode":"${doubleClick}"}"""
	if (doubleClick.contains("Preset")) {
		action2 = """{"mode":"customize_preset","index":${doubleClick[-1].toInteger()}}"""
	}
	sendCmd("""{"smartlife.iot.dimmer":{"set_double_click_action":${action2}, """ +
			""""set_long_press_action":${action1}}}""")

	runIn(1, getDimmerConfiguration)
}

def setDimmerConfig(response) {
	logDebug("setDimmerConfiguration: ${response}")
	def params
	def dimmerConfig = [:]
	if (response["get_dimmer_parameters"]) {
		params = response["get_dimmer_parameters"]
		if (params.err_code == "0") {
			logWarn("setDimmerConfig: Error in getDimmerParams: ${params}")
		} else {
			def fadeOn = getFade(params.fadeOnTime.toInteger())
			def fadeOff = getFade(params.fadeOffTime.toInteger())
			device.updateSetting("fadeOn", [type:"integer", value: fadeOn])
			device.updateSetting("fadeOff", [type:"integer", value: fadeOff])
			device.updateSetting("gentleOn", [type:"integer", value: params.gentleOnTime])
			device.updateSetting("gentleOff", [type:"integer", value: params.gentleOffTime])
			dimmerConfig << [fadeOn: fadeOn, fadeOff: fadeOff,
							 genleOn: gentleOn, gentleOff: gentleOff]
		}
	}
	if (response["get_default_behavior"]) {
		params = response["get_default_behavior"]
		if (params.err_code == "0") {
			logWarn("setDimmerConfig: Error in getDefaultBehavior: ${params}")
		} else {
			def longPress = params.long_press.mode
			if (params.long_press.index != null) { longPress = "Preset ${params.long_press.index}" }
			device.updateSetting("longPress", [type:"enum", value: longPress])
			def doubleClick = params.double_click.mode
			if (params.double_click.index != null) { doubleClick = "Preset ${params.double_click.index}" }
			device.updateSetting("doubleClick", [type:"enum", value: doubleClick])
			dimmerConfig << [longPress: longPress, doubleClick: doubleClick]
		}
	}
	logInfo("setDimmerConfig: ${dimmerConfig}")
}

def getFade(fadeTime) {
	def fadeSpeed = "Instant"
	if (fadeTime == 1000) {
		fadeSpeed = "Fast"
	} else if (fadeTime == 2000) {
		fadeSpeed = "Medium"
	} else if (fadeTime == 3000) {
		fadeSpeed = "Slow"
	}
	return fadeSpeed
}

def setLevel(level, transTime = gentleOn/1000) {
	setDimmerTransition(level, transTime)
	def updates = [:]
	updates << [switch: "on", level: level]
	sendEvent(name: "switch", value: "on", type: "digital")
	sendEvent(name: "level", value: level, type: "digital")
	logInfo("setLevel: ${updates}")
	runIn(9, getSysinfo)
}

def presetLevel(level) {
	presetBrightness(level)
}

def startLevelChange(direction) {
	logDebug("startLevelChange: [level: ${device.currentValue("level")}, direction: ${direction}]")
	if (device.currentValue("switch") == "off") {
		setRelayState(1)
		pauseExecution(1000)
	}
	if (direction == "up") { levelUp() }
	else { levelDown() }
}

def stopLevelChange() {
	logDebug("startLevelChange: [level: ${device.currentValue("level")}]")
	unschedule(levelUp)
	unschedule(levelDown)
}

def levelUp() {
	def curLevel = device.currentValue("level").toInteger()
	if (curLevel == 100) { return }
	def newLevel = curLevel + 4
	if (newLevel > 100) { newLevel = 100 }
	presetBrightness(newLevel)
	runIn(1, levelUp)
}

def levelDown() {
	def curLevel = device.currentValue("level").toInteger()
	if (curLevel == 0 || device.currentValue("switch") == "off") { return }
	def newLevel = curLevel - 4
	if (newLevel <= 0) { off() }
	else {
		presetBrightness(newLevel)
		runIn(1, levelDown)
	}
}

def setSysInfo(status) {
	def switchStatus = status.relay_state
	def logData = [:]
	def onOff = "on"
	if (switchStatus == 0) { onOff = "off" }
	if (device.currentValue("switch") != onOff) {
		sendEvent(name: "switch", value: onOff, type: "digital")
		logData << [switch: onOff]
	}
	if (device.currentValue("level") != status.brightness) {
		sendEvent(name: "level", value: status.brightness, type: "digital")
		logData << [level: status.brightness]
	}
	def ledStatus = status.led_off
	def ledOnOff = "on"
	if (ledStatus == 1) { ledOnOff = "off" }
	if (device.currentValue("led") != ledOnOff) {
		sendEvent(name: "led", value: ledOnOff)
		logData << [led: ledOnOff]
	}

	if (logData != [:]) {
		logInfo("setSysinfo: ${logData}")
	}
	if (nameSync == "device") {
		updateName(status)
	}
}

def checkTransTime(transTime) {
	if (transTime == null || transTime < 0.001) {
		transTime = gentleOn
	} else if (transTime == 0) {
		transTime = 50
	} else {
		transTime = transTime * 1000
	}
	
	if (transTime > 8000) { transTime = 8000 }
	return transTime.toInteger()
}

def checkLevel(level) {
	if (level == null || level < 0) {
		level = device.currentValue("level")
		logWarn("checkLevel: Entered level null or negative. Level set to ${level}")
	} else if (level > 100) {
		level = 100
		logWarn("checkLevel: Entered level > 100.  Level set to ${level}")
	}
	return level
}

def setDimmerTransition(level, transTime) {
	level = checkLevel(level)
	transTime = checkTransTime(transTime)
	logDebug("setDimmerTransition: [level: ${level}, transTime: ${transTime}]")
	if (level == 0) {
		setRelayState(0)
	} else {
		sendCmd("""{"smartlife.iot.dimmer":{"set_dimmer_transition":{"brightness":${level},""" +
				""""duration":${transTime}}}}""")
	}
}

def presetBrightness(level) {
	level = checkLevel(level)
	logDebug("presetLevel: [level: ${level}]")
	sendCmd("""{"smartlife.iot.dimmer":{"set_brightness":{"brightness":${level}}},""" +
			""""system" :{"get_sysinfo" :{}}}""")
}

def getDimmerConfiguration() {
	logDebug("getDimmerConfiguration")
	sendCmd("""{"smartlife.iot.dimmer":{"get_dimmer_parameters":{}, """ +
			""""get_default_behavior":{}}}""")
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
