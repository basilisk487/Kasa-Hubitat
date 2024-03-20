/*	Kasa Integration Application
	Copyright Dave Gutheinz
License:  https://github.com/DaveGut/HubitatActive/blob/master/KasaDevices/License.md
===== Link to Documentation =====
	https://github.com/DaveGut/HubitatActive/blob/master/KasaDevices/Documentation.pdf

Version 2.3.8a
	ISSUES
	1.	Some devices are not found on LAN using UDP - but still work via Kasa phone app
		and Hubitat (if IP is correct).  Sencond discovery sometimes alleviates this.
	2.	IP Ping pong caused by lan discovery not finding erroneous device (due to devices
		data not reset.
	OBJECTIVE:  Improve installation process to simplify and improve chances of user
				successfully installing all devices.  Make the cloud option	perfectly 
				clear to user.
	FIXES:
	a.	Added option to enter Kasa credentials on startPage.
	b.	Find Devices.
		1.  Zero state.devices as first step.  
		2.  If the credentials are set (valid token), Find devices will find via UDP AND
			CLOUD to capture missing devices.
		3.	During find process, will not send data to parse if a LAN device has already
			been discovered.
	c.	Add Devices Page.  Provide ist of all found devices.  Also has access to immediately 
		do an addition Find Devices (without resetting devices data).
	d.	RemoveDevices: Modified to not rely on state.devices.  Uses getChildDevices only.
	e.	Device Configure (app method).  Automatically completes two find devices runs to
		assure all devices are found.
	f.	Other cleanups to accommodate the Fixes and streamline the app options.
		1.	Modified: startPage, addDevicesPage, configureChildren, findDevices, getToken,
			kasaAuthenticationPage.
		2.	Removed: lanAddDevicesPage, manAddDevicesPage, manAddStart, cloudAddDevicesPage,
			cloudAddStart, listDevices, listDevicesByIp, listDevicesByName, getDeviceList,
			runLanTest, lanTestParse, lanTestResult, schedGetToken, startGetToken
		3.	Moved: kasaAuthenticationPage to startPage, commsTest to startPage.
		4.	Change logging to common library Logging.
===================================================================================================*/
//	=====	NAMESPACE	============
def nameSpace() { return "davegut" }
//	================================

import groovy.json.JsonSlurper
def getVer() { return "" }

definition(
	name: "Kasa Integration",
	namespace: nameSpace(),
	author: "Dave Gutheinz",
	description: "Application to install TP-Link bulbs, plugs, and switches.",
	category: "Convenience",
	iconUrl: "",
	iconX2Url: "",
	installOnOpen: true,
	singleInstance: true,
	documentationLink: "https://github.com/DaveGut/HubitatActive/blob/master/KasaDevices/README.md",
	importUrl: "https://raw.githubusercontent.com/DaveGut/HubitatActive/master/KasaDevices/Application/KasaIntegrationApp.groovy"
)

preferences {
	page(name: "initInstance")
	page(name: "startPage")
	page(name: "addDevicesPage")
	page(name: "addDevStatus")
	page(name: "kasaAuthenticationPage")
	page(name: "removeDevicesPage")
	page(name: "commsTest")
	page(name: "commsTestDisplay")
}

def installed() { updated() }

def updated() {
	logInfo("updated: Updating device configurations and (if cloud enabled) Kasa Token")
	app.updateSetting("logEnable", [type:"bool", value: false])
	app?.updateSetting("appSetup", [type:"bool", value: false])
	app?.removeSetting("utilities")
	app?.removeSetting("pingKasaDevices")
	app?.removeSetting("devAddresses")
	app?.removeSetting("devPort")
	state.remove("lanTest")
	state.remove("addedDevices")
	state.remove("failedAdds")
	state.remove("initialFind")
	app?.removeSetting("altInstall")
	scheduleChecks()
}

def scheduleChecks() {
	unschedule()
	configureEnable()
	if (kasaToken && kasaToken != "INVALID") {
		schedule("0 30 2 ? * MON,WED,SAT", getToken)
	}
}

def uninstalled() {
    getAllChildDevices().each { 
        deleteChildDevice(it.deviceNetworkId)
    }
}

def initInstance() {
	logDebug("initInstance: Getting external data for the app.")
	unschedule()
	runIn(900, scheduleChecks)
	app.updateSetting("infoLog", true)
	app.updateSetting("logEnable", false)
	runIn(900, debugLogOff)
	state.devices = [:]
	if (!lanSegment) {
		def hub = location.hub
		def hubIpArray = hub.localIP.split('\\.')
		def segments = [hubIpArray[0],hubIpArray[1],hubIpArray[2]].join(".")
		app?.updateSetting("lanSegment", [type:"string", value: segments])
	}
	if (!ports) {
		app?.updateSetting("ports", [type:"string", value: "9999"])
	}
	if (!hostLimits) {
		app?.updateSetting("hostLimits", [type:"string", value: "1, 254"])
	}
	startPage()
}

def startPage() {
	logInfo("starting Kasa Integration")
	if (selectedRemoveDevices) { removeDevices() }
	if (selectedAddDevices) { addDevices() }
	try {
		state.segArray = lanSegment.split('\\,')
		state.portArray = ports.split('\\,')
		def rangeArray = hostLimits.split('\\,')
		def array0 = rangeArray[0].toInteger()
		def array1 = array0 + 2
		if (rangeArray.size() > 1) {
			array1 = rangeArray[1].toInteger()
		}
		state.hostArray = [array0, array1]
	} catch (e) {
		logWarn("startPage: Invalid entry for Lan Segements, Host Array Range, or Ports. Resetting to default!")
		def hub = location.hubs[0]
		def hubIpArray = hub.localIP.split('\\.')
		def segments = [hubIpArray[0],hubIpArray[1],hubIpArray[2]].join(".")
		app?.updateSetting("lanSegment", [type:"string", value: segments])
		app?.updateSetting("ports", [type:"string", value: "9999"])
		app?.updateSetting("hostLimits", [type:"string", value: "1, 254"])
	}
	
	def notes = "<b>IMPORTANT NOTES:</b>\r"
	notes += "\t<b>HS300 Multiplug</b>.  Requires special handling.  Read install instructions.\r"
	
	return dynamicPage(name:"startPage",
					   title:"<b>Kasa Hubitat Integration</b>",
					   uninstall: true,
					   install: true) {
		section() {
			paragraph notes
			input "appSetup", "bool",
				title: "<b>Modify LAN Configuration</b>",
				submitOnChange: true,
				defaultalue: false
			if (appSetup) {
				input "lanSegment", "string",
					title: "<b>Lan Segments</b> (ex: 192.168.50, 192,168.01)",
					submitOnChange: true
				input "hostLimits", "string",
					title: "<b>Host Address Range</b> (ex: 5, 100)",
					submitOnChange: true
				input "ports", "string",
					title: "<b>Ports for Port Forwarding</b> (ex: 9999, 8000)",
					submitOnChange: true
			}
			paragraph "<b>LAN Configuration</b>:  [LanSegments: ${state.segArray},  " +
				"Ports ${state.portArray},  hostRange: ${state.hostArray}]"

			href "addDevicesPage",
				title: "<b>Scan LAN for Kasa devices and add</b>",
				description: "Primary Method to discover and add devices."
			
//			def desc = "<b>Requires two factor authentication is disabled in Kasa App.</b>"
			href "kasaAuthenticationPage",
				title: "<b>Set / Update Cloud Credentials (OPTIONAL)</b>",
				description: "<b>Requires two factor authentication is disabled in Kasa App.</b>"
			paragraph "<b>Current Kasa Token</b>: ${kasaToken}" 
			
			href "removeDevicesPage",
				title: "<b>Remove Kasa Devices</b>",
				description: "Select to remove selected Kasa Device from Hubitat."
			paragraph " "
			input "logEnable", "bool",
				   title: "<b>Debug logging</b>",
				   submitOnChange: true
			href "commsTest", title: "<b>IP Comms Ping Test Tool</b>",
				description: "Select for Ping Test Page."
		}
	}
}

def addDevicesPage() {
	logDebug("addDevicesPage")
	def action = findDevices(10)
	runIn(5, updateChildren)
	def devices = state.devices
	def uninstalledDevices = [:]
	def requiredDrivers = [:]
	devices.each {
		def isChild = getChildDevice(it.value.dni)
		if (!isChild) {
			uninstalledDevices["${it.value.dni}"] = "${it.value.alias}, ${it.value.type}"
			requiredDrivers["${it.value.type}"] = "${it.value.type}"
		}
	}
	uninstalledDevices.sort()
	def reqDrivers = []
	requiredDrivers.each {
		reqDrivers << it.key
	}
	
	def deviceList = []
	if (devices == null) {
		deviceList << "<b>No Devices in devices.</b>]"
	} else {
		devices.each{
			def dni = it.key
			def result = ["Failed", "n/a"]
			def installed = "No"
			def isChild = getChildDevice(it.key)
			if (isChild) {
				installed = "Yes"
			}
			deviceList << "<b>${it.value.alias}</b>: ${it.value.ip}:${it.value.port}, ${it.value.rssi}, ${installed}"
		}
	}
	deviceList.sort()
	def theList = ""
	deviceList.each {
		theList += "${it}\n"
	}

	return dynamicPage(name:"addDevicesPage",
					   title: "Add Kasa Devices to Hubitat",
					   nextPage: addDevStatus,
					   install: false) {
	 	section() {
			input ("selectedAddDevices", "enum",
				   required: false,
				   multiple: true,
				   title: "Devices to add (${uninstalledDevices.size() ?: 0} available).\n\t" +
				   "Total Devices: ${devices.size()}",
				   description: "Use the dropdown to select devices.  Then select 'Done'.",
				   options: uninstalledDevices)
			paragraph "<b>Found Devices: (Alias: Ip:Port, Strength, Installed?)</b>\r<p style='font-size:14px'>${theList}</p>"
			href "addDevicesPage",
				title: "<b>Rescan for Additional Kasa Devices</b>",
				description: "<b>Perform scan again to try to capture missing devices.</b>"
		}
	}
}

def addDevStatus() {
	addDevices()
	logInfo("addDevStatus")
	def addMsg = ""
	if (state.addedDevices == null) {
		addMsg += "Added Devices: No devices added."
	} else {
		addMsg += "<b>The following devices were installed:</b>\n"
		state.addedDevices.each{
			addMsg += "\t${it}\n"
		}
	}
	def failMsg = ""
	if (state.failedAdds) {
		failMsg += "<b>The following devices were not installed:</b>\n"
		state.failedAdds.each{
			failMsg += "\t${it}\n"
		}
	}
		
	return dynamicPage(name:"addDeviceStatus",
					   title: "Installation Status",
					   nextPage: startPage,
					   install: false) {
	 	section() {
			paragraph addMsg
			paragraph failMsg
		}
	}
	app?.removeSetting("selectedAddDevices")
}

def addDevices() {
	logInfo("addDevices: [selectedDevices: ${selectedAddDevices}]")
	def hub = location.hubs[0]
	state.addedDevices = []
	state.failedAdds = []
	selectedAddDevices.each { dni ->
		def isChild = getChildDevice(dni)
		if (!isChild) {
			def device = state.devices.find { it.value.dni == dni }
			def deviceData = [:]
			deviceData["deviceIP"] = device.value.ip
			deviceData["devicePort"] = device.value.port
			deviceData["deviceId"] = device.value.deviceId
			deviceData["model"] = device.value.model
			deviceData["feature"] = device.value.feature
			if (device.value.plugNo) {
				deviceData["plugNo"] = device.value.plugNo
				deviceData["plugId"] = device.value.plugId
			}
			try {
				addChildDevice(
					nameSpace(),
					device.value.type,
					device.value.dni,
					[
						"label": device.value.alias.replaceAll("[\u201C\u201D]", "\"").replaceAll("[\u2018\u2019]", "'").replaceAll("[^\\p{ASCII}]", ""),
						"data" : deviceData
					]
				)
				state.addedDevices << [label: device.value.alias, ip: device.value.ip]
				logDebug("Installed ${device.value.alias}.")
			} catch (error) {
				state.failedAdds << [label: device.value.alias, driver: device.value.type, ip: device.value.ip]
				def msgData = [status: "failedToAdd", label: device.value.alias, driver: device.value.type, ip: device.value.ip]
				msgData << [errorMsg: error]
				logWarn("addDevice: ${msgData}")
			}
		}
		pauseExecution(3000)
	}
	app?.removeSetting("selectedAddDevices")
}

def kasaAuthenticationPage() {
	logInfo([method: "kasaAuthenticationPage"])
	return dynamicPage (name: "kasaAuthenticationPage", 
    					title: "Initial Kasa Login Page",
						nextPage: startPage,
                        install: false) {
		def note = "You only need to enter your Kasa credentials and get a token " +
			"if LAN discovery could not find all devices. " +
			"\na.\tEnter the credentials and get a token" +
			"\nb.\tRun Install Kasa Devices" +
			"\nc.\tTo stop using the cloud, simply zero out the username or password."
		section("Enter Kasa Account Credentials: ${note}") {
			input ("userName", "email",
            		title: "<b>TP-Link Kasa Email Address</b>", 
                    required: false,
                    submitOnChange: true)
			input ("userPassword", "password",
            		title: "<b>TP-Link Kasa Account Password</b>",
                    required: false,
                    submitOnChange: true)
			if (userName && userPassword) {
				def await = getToken("kasaAuthenticationPage")
			} else {
				app?.updateSetting("kasaToken", "INVALID")
			}
			paragraph "<b>Current Kasa Token</b>: ${kasaToken}" 
		}
	}
}

def getToken(source = "scheduled") {
	Map logData = [method: "getToken", source: source]
	def termId = java.util.UUID.randomUUID()
	def cmdBody = [
		method: "login",
		params: [
			appType: "Kasa_Android",
			cloudUserName: "${userName}",
			cloudPassword: "${userPassword.replaceAll('&gt;', '>').replaceAll('&lt;','<')}",
			terminalUUID: "${termId}"]]
	cmdData = [uri: "https://wap.tplinkcloud.com",
			   cmdBody: cmdBody]
	def respData = sendKasaCmd(cmdData)
	if (respData.error_code == 0) {
		token = respData.result.token
		logData << [newTokenLength: token.length()]
		app?.updateSetting("kasaToken", respData.result.token)
		if (!kasaCloudUrl) {
			logData << getCloudUrl()
		}
	} else {
		app?.updateSetting("kasaToken", "INVALID")
		logData << [updateFailed: respData]
		logWarn(logData)
		if (source == "scheduled") {
			runIn(600, getToken)
		}
	}
	logInfo(logData)
	pauseExecution(2000)
	return
}

def getCloudUrl() {
	Map logData = [method: "getCloudUrl"]
	def cmdData = [uri: "https://wap.tplinkcloud.com?token=${kasaToken}", 
				   cmdBody: [method: "getDeviceList"]]
	def respData = sendKasaCmd(cmdData)
	if (respData.error_code == 0) {
		def cloudDevices = respData.result.deviceList
		def cloudUrl = cloudDevices[0].appServerUrl
		logData << [kasaCloudUrl: cloudUrl]
		app?.updateSetting("kasaCloudUrl", cloudUrl)
	} else {
		logData << [error: "Devices not returned from Kasa Cloud", data: respData]
		logWarn(logData)
	}
	return logData
}

def findDevices(timeout = 5) {
	def start = state.hostArray.min().toInteger()
	def finish = state.hostArray.max().toInteger() + 1
	def await
	logDebug([method: "findDevices", hostArray: state.hostArray, portArray: state.portArray, 
			 pollSegment: state.segArray, timeout: timeout])
	state.portArray.each {
		def port = it.trim()
		List deviceIPs = []
		state.segArray.each {
			def pollSegment = it.trim()
			logDebug("findDevices: Searching for LAN deivces on IP Segment = ${pollSegment}, port = ${port}")
            for(int i = start; i < finish; i++) {
				deviceIPs.add("${pollSegment}.${i.toString()}")
			}
			logInfo([method: "findDevices", activity: "sendLanCmd", segment: pollSegment, port: port])
			await = sendLanCmd("${pollSegment}.255", port, """{"system":{"get_sysinfo":{}}}""", "parseWakeup", 5, true)
			pauseExecution(5000)
			await = sendLanCmd(deviceIPs.join(','), port, """{"system":{"get_sysinfo":{}}}""", "getLanData", timeout)
			pauseExecution(1000*(timeout + 1))
		}
	}
	pauseExecution(5000)
	if (kasaToken && kasaToken != "INVALID") {
		logInfo([method: "findDevices", activity: "cloudGetDevices"])
		await = cloudGetDevices()
	}
	return
}

def parseWakeup(response) { logInfo([method: "parseWakeup"]) }

def getLanData(response) {
	if (response instanceof Map) {
		def lanData = parseLanData(response)
		if (lanData.error) { return }
		def cmdResp = lanData.cmdResp
		if (cmdResp.system) {
			cmdResp = cmdResp.system
		}
		def await = parseDeviceData(cmdResp, lanData.ip, lanData.port)
	} else {
		devices = state.devices
		response.each {
			def lanData = parseLanData(it)
			if (lanData.error) { return }
			def cmdResp = lanData.cmdResp
			if (cmdResp.system) {
				cmdResp = cmdResp.system
			}
			def await = parseDeviceData(cmdResp, lanData.ip, lanData.port)
		}
	}
}

def cloudGetDevices() {
	Map logData = [method: "cloudGetDevices"]
	def message = ""
	def cmdData = [uri: "https://wap.tplinkcloud.com?token=${kasaToken}", 
				   cmdBody: [method: "getDeviceList"]]
	def respData = sendKasaCmd(cmdData)
	def cloudDevices
	def cloudUrl
	if (respData.error_code == 0) {
		cloudDevices = respData.result.deviceList
		cloudUrl = ""
	} else {
		message = "Devices not returned from Kasa Cloud."
		logData << [error: message, data: respData]
		logWarn(logData)
		return
	}
	Map devices = state.devices
	cloudDevices.each { childDev ->
		def devData = devices.find { it.value.deviceId == childDev.deviceId }
		if (devData && devData.value.deviceId == childDev.deviceId) {
			logDebug([ignoredDevice: childDev.deviceMac, reason: "already in array"])
		} else if (childDev.deviceType != "IOT.SMARTPLUGSWITCH" &&
				   childDev.deviceType != "IOT.SMARTBULB" && 
				   childDev.deviceType != "IOT.IPCAMERA") {
			logDebug([ignoredDevice: childDev.deviceMac, reason: "unsupported deviceType"])
		} else if (childDev.status == 0) {
			logDebug([ignoredDevice: childDev.deviceMac, reason: "not controllable via cloud"])
		} else {
			cloudUrl = childDev.appServerUrl
			def cmdBody = [
				method: "passthrough",
				params: [
					deviceId: childDev.deviceId,
					requestData: """{"system":{"get_sysinfo":{}}}"""]]
			cmdData = [uri: "${cloudUrl}/?token=${kasaToken}",
					   cmdBody: cmdBody]
			def cmdResp
			respData = sendKasaCmd(cmdData)
			if (respData.error_code == 0) {
				def jsonSlurper = new groovy.json.JsonSlurper()
				cmdResp = jsonSlurper.parseText(respData.result.responseData).system.get_sysinfo
				if (cmdResp.system) {
					cmdResp = cmdResp.system
				}
///////////////////
				def await = parseDeviceData(cmdResp)
			}
		}
	}
	logData << [status: "added cloud-only devices"]
	if (cloudUrl != "" && cloudUrl != kasaCloudUrl) {
		app?.updateSetting("kasaCloudUrl", cloudUrl)
		logData << [kasaCloudUrl: cloudUrl]
	}
	logDebug(logData)
//	pauseExecution(2000)
	return
}

def parseDeviceData(cmdResp, ip = "CLOUD", port = "CLOUD") {
	def logData = [method: "parseDeviceData"]
	def dni
	if (cmdResp.mic_mac) {
		dni = cmdResp.mic_mac
	} else {
		dni = cmdResp.mac.replace(/:/, "")
	}
	def devices = state.devices
	def kasaType
	if (cmdResp.mic_type) {
		kasaType = cmdResp.mic_type
	} else {
		kasaType = cmdResp.type
	}
	def type = "Kasa Plug Switch"
	def feature = cmdResp.feature
	if (kasaType == "IOT.SMARTPLUGSWITCH") {
		if (cmdResp.dev_name && cmdResp.dev_name.contains("Dimmer")) {
			feature = "dimmingSwitch"
			type = "Kasa Dimming Switch"
		}
	} else if (kasaType == "IOT.SMARTBULB") {
		if (cmdResp.lighting_effect_state) {
			feature = "lightStrip"
			type = "Kasa Light Strip"
		} else if (cmdResp.is_color == 1) {
			feature = "colorBulb"
			type = "Kasa Color Bulb"
		} else if (cmdResp.is_variable_color_temp == 1) {
			feature = "colorTempBulb"
			type = "Kasa CT Bulb"
		} else {
			feature = "monoBulb"
			type = "Kasa Mono Bulb"
		}
	} else if (kasaType == "IOT.IPCAMERA") {
		feature = "ipCamera"
		type = "NOT AVAILABLE"
	}
	def model = cmdResp.model.substring(0,5)
	def alias = cmdResp.alias
	def rssi = cmdResp.rssi
	def deviceId = cmdResp.deviceId
	def plugNo
	def plugId
	if (cmdResp.children) {
		def childPlugs = cmdResp.children
		childPlugs.each {
			plugNo = it.id
			plugNo = it.id.substring(it.id.length() - 2)
			def childDni = "${dni}${plugNo}"
			plugId = "${deviceId}${plugNo}"
			alias = it.alias
			def device = createDevice(childDni, ip, port, rssi, type, feature, model, alias, deviceId, plugNo, plugId)
			devices["${childDni}"] = device
		}
	} else if (model == "HS300") {
		def parentAlias = alias
		for(int i = 0; i < 6; i++) {
			plugNo = "0${i.toString()}"
			def childDni = "${dni}${plugNo}"
			plugId = "${deviceId}${plugNo}"
			def child = getChildDevice(childDni)
			if (child) {
				alias = child.device.getLabel()
			} else {
				alias = "${parentAlias}_${plugNo}_TEMP"
			}
			def device = createDevice(childDni, ip, port, rssi, type, feature, model, alias, deviceId, plugNo, plugId)
			devices["${childDni}"] = device
		}
	} else {
		def device = createDevice(dni, ip, port, rssi, type, feature, model, alias, deviceId, plugNo, plugId)
		devices["${dni}"] = device
	}
	logData << [alias: "<b>${alias}</b>", type: type, ip: ip, port: port, status: "added to array"]
	logDebug(logData)
	return
}

def createDevice(dni, ip, port, rssi, type, feature, model, alias, deviceId, plugNo, plugId) {
	Map logData = [method: "createDevice"]
	def device = [:]
	device["dni"] = dni
	device["ip"] = ip
	device["port"] = port
	device["type"] = type
	device["rssi"] = rssi
	device["feature"] = feature
	device["model"] = model
	device["alias"] = alias
	device["deviceId"] = deviceId
	if (plugNo != null) {
		device["plugNo"] = plugNo
		device["plugId"] = plugId
		logData << [plugNo: plugNo]
	}
	logData << [device: device]
	logDebug(logData)
	return device
}

def removeDevicesPage() {
	Map logData = [method: "removeDevicesPage"]
	def installedDevices = [:]
	getChildDevices().each {
		installedDevices << ["${it.device.deviceNetworkId}": it.device.label]
	}
	logData << [installedDevices: installedDevices]
	logData << [childDevices: installedDevices]
	logInfo(logData)
	return dynamicPage(name:"removedDevicesPage",
					   title:"<b>Remove Kasa Devices from Hubitat</b>",
					   nextPage: startPage,
					   install: false) {
		section() {
			input ("selectedRemoveDevices", "enum",
				   required: false,
				   multiple: true,
				   title: "Devices to remove (${installedDevices.size() ?: 0} available)",
				   description: "Use the dropdown to select devices.  Then select 'Done'.",
				   options: installedDevices)
		}
	}
}

def removeDevices() {
	Map logData = [method: "removeDevices", selectedRemoveDevices: selectedRemoveDevices]
	selectedRemoveDevices.each { dni ->
		try {
			deleteChildDevice(dni)
			logData << ["${dni}": "removed"]
		} catch (error) {
			logData << ["${dni}": "FAILED"]
			logWarn("Failed to delet ${device.value.alias}.")
		}
	}
	app?.removeSetting("selectedRemoveDevices")
	logInfo(logData)
}

def commsTest() {
	logInfo("commsTest")
	return dynamicPage(name:"commsTest",
					   title: "IP Communications Test",
					   nextPage: startPage,
					   install: false) {
	 	section() {
			def note = "This test measures ping from this Hub to any device on your  " +
				"LAN (wifi and connected). You enter your Router's IP address, a " +
				"non-Kasa device (other hub if you have one), and select the Kasa " +
				"devices to ping. (Each ping will take about 3 seconds)."
			paragraph note
			input "routerIp", "string",
				title: "<b>IP Address of your Router</b>",
				required: false,
				submitOnChange: true
			input "nonKasaIp", "string",
				title: "<b>IP Address of non-Kasa LAN device (other Hub?)</b>",
				required: false,
				submitOnChange: true

			def devices = state.devices
			def kasaDevices = [:]
			devices.each {
				kasaDevices["${it.value.dni}"] = "${it.value.alias}, ${it.value.ip}"
 			}
			input ("pingKasaDevices", "enum",
				   required: false,
				   multiple: true,
				   title: "Kasa devices to ping (${kasaDevices.size() ?: 0} available).",
				   description: "Use the dropdown to select devices.",
				   options: kasaDevices)
			paragraph "Test will take approximately 5 seconds per device."
			href "commsTestDisplay", title: "<b>Ping Selected Devices</b>",
				description: "Click to Test IP Comms."
		}
	}
}

def commsTestDisplay() {
	logDebug("commsTestDisplay: [routerIp: ${routerIp}, nonKasaIp: ${nonKasaIp}, kasaDevices: ${pingKasaDevices}]")
	def pingResults = []
	def pingResult
	if (routerIp != null) {
		pingResult = sendPing(routerIp, 5)
		pingResults << "<b>Router</b>: ${pingResult}"
	}
	if (nonKasaIp != null) {
		pingResult = sendPing(nonKasaIp, 5)
		pingResults << "<b>nonKasaDevice</b>: ${pingResult}"
	}
	def devices = state.devices
	if (pingKasaDevices != null) {
		pingKasaDevices.each {dni ->
			def device = devices.find { it.value.dni == dni }
			pingResult = sendPing(device.value.ip, 5)
			pingResults << "<b>${device.value.alias}</b>: ${pingResult}"
		}
	}
	def pingList = ""
	pingResults.each {
		pingList += "${it}\n"
	}
	return dynamicPage(name:"commsTestDisplay",
					   title: "Ping Testing Result",
					   nextPage: commsTest,
					   install: false) {
		section() {
			def note = "<b>Expectations</b>:\na.\tAll devices have similar ping results." +
				"\nb.\tAll pings are less than 1000 ms.\nc.\tSuccess is 100." +
				"\nIf not, test again to verify bad results." +
				"\nAll times are in ms. Success is percent of 5 total tests."
			paragraph note
			paragraph "<p style='font-size:14px'>${pingList}</p>"
		}
	}
}

def sendPing(ip, count = 3) {
	hubitat.helper.NetworkUtils.PingData pingData = hubitat.helper.NetworkUtils.ping(ip, count)
	def success = "nullResults"
	def minTime = "n/a"
	def maxTime = "n/a"
	if (pingData) {
		success = (100 * pingData.packetsReceived.toInteger()  / count).toInteger()
		minTime = pingData.rttMin
		maxTime = pingData.rttMax
	}
	def pingResult = [ip: ip, min: minTime, max: maxTime, success: success]
	return pingResult
}

def updateConfigurations() {
	Map logData = [method: "updateConfiguration", configureEnabled: configureEnabled]
	if (configureEnabled) {
		app?.updateSetting("configureEnabled", [type:"bool", value: false])
		configureChildren()
		runIn(600, configureEnable)
		logData << [status: "executing configureChildren"]
	} else {
		logData << [status: "notRun", data: "method rn with past 10 minutes"]
	}
	logInfo(logData)
	return logData
}

def configureEnable() {
	logDebug("configureEnable: Enabling configureDevices")
	app?.updateSetting("configureEnabled", [type:"bool", value: true])
}

def configureChildren() {
	state.devices = [:]
	def await = findDevices(10)
	runIn(2, updateChildren)
}

def updateChildren() {
	Map logData = [method: "updateChildren"]
	def children = getChildDevices()
	def devices = state.devices
	children.each { childDev ->
		Map childData = [:]
		def device = devices.find { it.value.dni == childDev.getDeviceNetworkId() }
		if (device == null) {
			if (childDev.getDataValue("deviceIP") != "CLOUD") {
				childDev.updateAttr("commsError", "true")
				childData << [commsError: "true"]
			} else {
				childData << [status: "No update. CLOUD Device."]
			}
		} else {
			childDev.updateAttr("commsError", "false")
			childData << [commsError: "false"]
			if (childDev.getDataValue("deviceIP") != device.value.ip ||
				childDev.getDataValue("devicePort") != device.value.port.toString()) {
				childDev.updateDataValue("deviceIP", device.value.ip)
				childDev.updateSetting("manualIp", [type:"string", value: device.value.ip])
				childDev.updateDataValue("devicePort", device.value.port.toString())
				childDev.updateSetting("manualPort", [type:"string", value: device.value.port.toString()])
				childData << [ip: device.value.ip, port: device.value.port]
			}
		}
		logData << ["${childDev}": childData]
	}
	logInfo(logData)
	return
}

def syncBulbPresets(bulbPresets) {
	logDebug("syncBulbPresets")
	def devices = state.devices
	devices.each {
		def type = it.value.type
		if (type == "Kasa Color Bulb" || type == "Kasa Light Strip") {
			def child = getChildDevice(it.value.dni)
			if (child) {
				child.updatePresets(bulbPresets)
			}
		}
	}
}

def resetStates(deviceNetworkId) {
	logDebug("resetStates: ${deviceNetworkId}")
	def devices = state.devices
	devices.each {
		def type = it.value.type
		def dni = it.value.dni
		if (type == "Kasa Light Strip") {
			def child = getChildDevice(dni)
			if (child && dni != deviceNetworkId) {
				child.resetStates()
			}
		}
	}
}

def syncEffectPreset(effData, deviceNetworkId) {
	logDebug("syncEffectPreset: ${effData.name} || ${deviceNetworkId}")
	def devices = state.devices
	devices.each {
		def type = it.value.type
		def dni = it.value.dni
		if (type == "Kasa Light Strip") {
			def child = getChildDevice(dni)
			if (child && dni != deviceNetworkId) {
				child.updateEffectPreset(effData)
			}
		}
	}
}

def coordinate(cType, coordData, deviceId, plugNo) {
	logDebug("coordinate: ${cType}, ${coordData}, ${deviceId}, ${plugNo}")
	def plugs = state.devices.findAll{ it.value.deviceId == deviceId }
	plugs.each {
		if (it.value.plugNo != plugNo) {
			def child = getChildDevice(it.value.dni)
			if (child) {
				child.coordUpdate(cType, coordData)
				pauseExecution(200)
			}
		}
	}
}

private sendLanCmd(ip, port, command, action, commsTo = 5, ignore = false) {
	def myHubAction = new hubitat.device.HubAction(
		outputXOR(command),
		hubitat.device.Protocol.LAN,
		[type: hubitat.device.HubAction.Type.LAN_TYPE_UDPCLIENT,
		 destinationAddress: "${ip}:${port}",
		 encoding: hubitat.device.HubAction.Encoding.HEX_STRING,
		 ignoreResponse: ignore,
		 parseWarning: true,
		 timeout: commsTo,
		 callback: action])
	try {
		sendHubCommand(myHubAction)
	} catch (error) {
		logWarn("sendLanCmd: command to ${ip}:${port} failed. Error = ${error}")
	}
	return
}

def parseLanData(response) {
	def resp = parseLanMessage(response.description)
	if (resp.type == "LAN_TYPE_UDPCLIENT") {
		def ip = convertHexToIP(resp.ip)
		def port = convertHexToInt(resp.port)
		def clearResp = inputXOR(resp.payload)
		def cmdResp
		try {
			cmdResp = new JsonSlurper().parseText(clearResp).system.get_sysinfo
		} catch (err) {
			if (clearResp.contains("child_num")) {
				clearResp = clearResp.substring(0,clearResp.indexOf("child_num")-2) + "}}}"
			} else if (clearResp.contains("children")) {
				clearResp = clearResp.substring(0,clearResp.indexOf("children")-2) + "}}}"
			} else if (clearResp.contains("preferred")) {
				clearResp = clearResp.substring(0,clearResp.indexOf("preferred")-2) + "}}}"
			} else {
				logWarn("parseLanData: [error: msg too long, data: ${clearResp}]")
				return [error: "error", reason: "message to long"]
			}
			cmdResp = new JsonSlurper().parseText(clearResp).system.get_sysinfo
		}
		return [cmdResp: cmdResp, ip: ip, port: port]
	} else {
		return [error: "error", reason: "not LAN_TYPE_UDPCLIENT", respType: resp.type]
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
	for(int i = 0; i < strBytes.length-1; i++) {
		nextKey = (byte)Integer.parseInt(strBytes[i], 16)	// could be negative
		XORtemp = nextKey ^ key
		key = nextKey
		cmdResponse += new String(XORtemp)
	}
	return cmdResponse
}

def sendKasaCmd(cmdData) {
	def commandParams = [
		uri: cmdData.uri,
		requestContentType: 'application/json',
		contentType: 'application/json',
		headers: ['Accept':'application/json; version=1, */*; q=0.01'],
		body : new groovy.json.JsonBuilder(cmdData.cmdBody).toString()
	]
	def respData
	try {
		httpPostJson(commandParams) {resp ->
			if (resp.status == 200) {
				respData = resp.data
			} else {
				def msg = "sendKasaCmd: <b>HTTP Status not equal to 200.  Protocol error.  "
				msg += "HTTP Protocol Status = ${resp.status}"
				logWarn(msg)
				respData = [error_code: resp.status, msg: "HTTP Protocol Error"]
			}
		}
	} catch (e) {
		def msg = "sendKasaCmd: <b>Error in Cloud Communications.</b> The Kasa Cloud is unreachable."
		msg += "\nAdditional Data: Error = ${e}\n\n"
		logWarn(msg)
		respData = [error_code: 9999, msg: e]
	}
	return respData
}

private String convertHexToIP(hex) {
	[convertHexToInt(hex[0..1]),convertHexToInt(hex[2..3]),convertHexToInt(hex[4..5]),convertHexToInt(hex[6..7])].join(".")
}

private Integer convertHexToInt(hex) { Integer.parseInt(hex,16) }

// ~~~~~ start include (67) davegut.Logging ~~~~~
library ( // library marker davegut.Logging, line 1
	name: "Logging", // library marker davegut.Logging, line 2
	namespace: "davegut", // library marker davegut.Logging, line 3
	author: "Dave Gutheinz", // library marker davegut.Logging, line 4
	description: "Common Logging and info gathering Methods", // library marker davegut.Logging, line 5
	category: "utilities", // library marker davegut.Logging, line 6
	documentationLink: "" // library marker davegut.Logging, line 7
) // library marker davegut.Logging, line 8

def label() { // library marker davegut.Logging, line 10
	if (device) { return device.displayName }  // library marker davegut.Logging, line 11
	else { return app.getLabel() } // library marker davegut.Logging, line 12
} // library marker davegut.Logging, line 13

def listAttributes() { // library marker davegut.Logging, line 15
	def attrData = device.getCurrentStates() // library marker davegut.Logging, line 16
	Map attrs = [:] // library marker davegut.Logging, line 17
	attrData.each { // library marker davegut.Logging, line 18
		attrs << ["${it.name}": it.value] // library marker davegut.Logging, line 19
	} // library marker davegut.Logging, line 20
	return attrs // library marker davegut.Logging, line 21
} // library marker davegut.Logging, line 22

def setLogsOff() { // library marker davegut.Logging, line 24
	def logData = [logEnable: logEnable] // library marker davegut.Logging, line 25
	if (logEnable) { // library marker davegut.Logging, line 26
		runIn(1800, debugLogOff) // library marker davegut.Logging, line 27
		logData << [debugLogOff: "scheduled"] // library marker davegut.Logging, line 28
	} // library marker davegut.Logging, line 29
	return logData // library marker davegut.Logging, line 30
} // library marker davegut.Logging, line 31

def logTrace(msg){ log.trace "${label()} ${getVer()}: ${msg}" } // library marker davegut.Logging, line 33

def logInfo(msg) {  // library marker davegut.Logging, line 35
	if (infoLog) { log.info "${label()} ${getVer()}: ${msg}" } // library marker davegut.Logging, line 36
} // library marker davegut.Logging, line 37

def debugLogOff() { // library marker davegut.Logging, line 39
	if (device) { // library marker davegut.Logging, line 40
		device.updateSetting("logEnable", [type:"bool", value: false]) // library marker davegut.Logging, line 41
	} else { // library marker davegut.Logging, line 42
		app.updateSetting("logEnable", false) // library marker davegut.Logging, line 43
	} // library marker davegut.Logging, line 44
	logInfo("debugLogOff") // library marker davegut.Logging, line 45
} // library marker davegut.Logging, line 46

def logDebug(msg) { // library marker davegut.Logging, line 48
	if (logEnable) { log.debug "${label()} ${getVer()}: ${msg}" } // library marker davegut.Logging, line 49
} // library marker davegut.Logging, line 50

def logWarn(msg) { log.warn "${label()} ${getVer()}: ${msg}" } // library marker davegut.Logging, line 52

def logError(msg) { log.error "${label()} ${getVer()}}: ${msg}" } // library marker davegut.Logging, line 54

// ~~~~~ end include (67) davegut.Logging ~~~~~
