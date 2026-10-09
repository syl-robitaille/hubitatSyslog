/**
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 *
 * Latest version is available at
 * https://github.com/syl-robitaille/hubitatSyslog
 *
 */

/* Notes

2020-08-18 - staylorx
  - A couple of dumb coding errors, and still trying to sort out TCP.
  - Received version from original author (great start!).
  - Attemping RFC5424 format for syslog.
  - Date/time stamping with the hub timezone.

2024-01-29 - rmonk
  - Changed the logging init delay to 10 seconds to try and get it
    to properly start on boot

2024-12-10 - Sylvain Robitaille
  - "hostname" input field defaults to the hub's internal name.
  - Syslog facility is configurable / priority is derived from hub's
    log "level".
  - rfc5424 syslog protocol version is identified as such, rather
    than being just a magical "1".
  - jtp10181's escapeStringHTMLforMsg() method is applied to the
    incoming log message.

2024-12-28 - Sylvain Robitaille
  - Differentiate between "apps" and "devs" in the logged messages.
  - Improve loop avoidance: check that the incoming message wasn't
    from a "dev" in addition to checking that its id number doesn't
    match our own.

2025-01-24 - Anne Bennett
  - Log at "info" level instead of debug in start-up code.
  - Add some logging of error conditions.

2025-02-13 Anne Bennett
  - Improve some of the log messages.

2026-08-27 - Anne Bennett (with Claude.AI)
  - Bugfix: "logger" doesn't exist.
  - Replace "init delay": Address bug whereby sometimes connection
    fails at start-up: try to reconnect in such cases.  We record
    when we last received a message, and a reconnect happens only if
    nothing has been received recently.
  - A periodic watchdog (every watchdogMinutes, default 5) applies the
    same test, so a websocket that dies without reporting a "failure"
    status (which is every case observed here since 2025) gets noticed
    by the hub itself rather than only by a syslog receiver's cron
    job (if present).  The watchdog uses its own handler name so that
    other entities scheduling a connection check cannot disturb the
    one-shot checks scheduled by initialize().
  - checkConnection() is exposed as a command, so that the watchdog's
    logic can be invoked on demand for diagnosis rather than only on
    its timer.
  - Loop avoidance is no longer "not me" but "not one of us", to avoid
    reciprocal logging loops involving several instances of this driver.
  - updated() warns when debug logging is switched on while a sibling
    device is registered.
  - The "No log server set" complaint moves from a per-message warn in
    parse() to a warn in connect(), where it is not subject to the
    debug-or-trace-only rule for preventing loops.
  - Removed no-longer-needed timezone-related debugging.
  - Compressed the "sending" and "via UDP|TCP" debug log lines into one.
  - Improve loop avoidance: track multiple instances of the driver.

2026-10-09 - Scott A. Johnson (with Claude)
  - Bugfix: missing ")" closing the "port" preference input.
*/

metadata {
    definition (
        name: "Syslog",
        namespace: "hubitatuser12",
        author: "Hubitat User 12, staylorx, jtp10181, Sylvain Robitaille (TheRockgarden), Anne Bennett"
    )
    {
        capability "Initialize"
    }
    // commands exposed to the outside so that Maker API can invoke them
    command "disconnect"
    command "checkConnection"

    preferences {
        input("ip", "text",
              title: "Syslog Destination",
              description: "IP address or hostname of the syslog server",
              required: true)
        input("port", "number",
              title: "Syslog IP Port",
              description: "Syslog server port number",
              defaultValue: 514)
        input("udptcp", "enum",
              title: "Syslog server protocol",
              description: "UDP or TCP?",
              defaultValue: "UDP",
              options: ["UDP","TCP"])
        input("logFacility", "enum",
              title: "Log Facility",
              description: "",
              defaultValue: "local0",
              options: ["kern","user","mail","daemon","auth","syslog","lpr","news","uucp","cron","authpriv","ftp","ntp","security","console","local0","local1","local2","local3","local4","local5","local6","local7"])
        input("hostname", "text",
              title: "Hub Hostname",
              description: "Hostname of this hub; leave empty for IP address",
              defaultValue: location.hub["name"])
        input("watchdogMinutes", "enum",
              title: "Watchdog frequency",
              description: "How often to check that we are still receiving, in case we lost our inbound connection.",
              options: [ "never": "disabled",
                         "1":     "every minute",
                         "5":     "every 5 minutes",
                         "10":    "every 10 minutes",
                         "15":    "every 15 minutes",
                         "30":    "every 30 minutes",
                         "60":    "every hour",
                         "180":   "every 3 hours" ],
              defaultValue: "5")
        input("staleMinutes", "number",
              title: "Longest normal quiet period",
              description: "Assume that something's wrong with the inbound websocket connection if nothing has been received for this many minutes; this must be comfortably longer than the hub's quietest normal spell.",
              defaultValue: 10)
        input("logEnable", "bool",
              title: "Enable debug logging",
              description: "(turns itself off after half an hour)",
              defaultValue: false)
    }
}

import groovy.json.JsonSlurper
import groovy.transform.Field    // gives us typed, script-wide variables
import hubitat.device.HubAction
import hubitat.device.Protocol

// RFC 5424 syslog protocol version.
@Field static final Integer syslogVersion = 1

// Every instance of this driver registers its own device id here, so that
// each of us can recognize a log message coming from a sibling Syslog device
// and not merely from ourselves.
@Field static final java.util.concurrent.ConcurrentHashMap syslogSiblings = new java.util.concurrent.ConcurrentHashMap()

// For syslogging:
@Field static final Map facilityMap =
    [
        "kern":       (0<<3),
        "user":       (1<<3),
        "mail":       (2<<3),
        "daemon":     (3<<3),
        "auth":       (4<<3),
        "syslog":     (5<<3),
        "lpr":        (6<<3),
        "news":       (7<<3),
        "uucp":       (8<<3),
        "cron":       (9<<3),
        "authpriv":  (10<<3),
        "ftp":       (11<<3),
        "ntp":       (12<<3),
        "security":  (13<<3),
        "console":   (14<<3),
//      "unused":    (15<<3),  /* Clock/cron daemon (Solaris) */
        "local0":    (16<<3),
        "local1":    (17<<3),
        "local2":    (18<<3),
        "local3":    (19<<3),
        "local4":    (20<<3),
        "local5":    (21<<3),
        "local6":    (22<<3),
        "local7":    (23<<3),
    ]
@Field static final Map priorityMap =
    [
        "emergency": 0,  
        "emerg":     0,
        "panic":     0,
        "alert":     1,
        "critical":  2,  
        "crit":      2,
        "error":     3,
        "err":       3,
        "warning":   4,
        "warn":      4,
        "notice":    5,
        "info":      6,
        "debug":     7,
        "trace":     7,
    ]


// =================== platform callbacks ================================

void installed() {
    log.info "installed()"
    updated()
}

void uninstalled() {
    log.info "uninstalled()"
    unschedule()
    disconnect()
}

void updated() {
    log.info "updated()"
    initialize()

    // Registration happens in initialize(), so by now we know about any
    // sibling that has been active.
    if (logEnable && syslogSiblings.size() > 1) {
        String siblingList = syslogSiblings.collect { id, label -> label + " (" + id + ")" }.join(", ")
        log.warn "Debug logging is on, and this hub has more than one Syslog device: " +
                 siblingList +
                 ".  While this device's own debug logging is on, it will not forward" +
                 " a sibling's debug messages, lest the two of them amplify each other" +
                 " without limit."
    }

    // Arrange to turn off debug logging after 30 minutes.
    if (logEnable) log.debug "updated(): will turn off debug logging in 30 minutes"
    if (logEnable) runIn(1800, "disableDebugLogging")
}

void initialize() {
    // First, set constant-ish variables.
    setHubDisplayName()

    // Register ourself so that sibling instances of this driver know
    // we're alive.  This happens also in parse(), so that our registration
    // is self-correcting after a re-save of the driver code.
    syslogSiblings["${device.id}"] = device.displayName

    // After a reboot, the web socket quite often reports "open", yet then delivers
    // nothing to us.  Rather than reconnecting blindly on a timer -- which could
    // tear down the connection in cases where it has actually worked --
    // we'll come back a few times and reconnect only if nothing has been received.
    log.info "initialize(): connecting in 2 seconds, with checks at 20, 60, and 180 seconds"

    // Clear previous state where relevant
    state.remove("deliberateDisconnect")
    state.lastReceived = 0

    // Clear previous scheduled jobs where relevant
    unschedule("connect")
    unschedule("checkConnection")
    unschedule("postBootCheck")
    unschedule("watchdog")

    // Schedule everything that needs to happen
    runIn(2,   "connect")
    runIn(20,  "postBootCheck", [overwrite: false])
    runIn(60,  "postBootCheck", [overwrite: false])
    runIn(180, "postBootCheck", [overwrite: false])

    String watchdogEvery = (watchdogMinutes == null) ? "5" : watchdogMinutes.toString()
    Integer staleAfter   = (staleMinutes ?: 10)
    switch (watchdogEvery)
    {
        case "never": break
        case "1":     runEvery1Minute("watchdog");   break
        case "5":     runEvery5Minutes("watchdog");  break
        case "10":    runEvery10Minutes("watchdog"); break
        case "15":    runEvery15Minutes("watchdog"); break
        case "30":    runEvery30Minutes("watchdog"); break
        case "60":    runEvery1Hour("watchdog");     break
        case "180":   runEvery3Hours("watchdog");    break

        // Only "never" disables the watchdog, and only when chosen on
        // purpose.  Anything we do not recognize falls back to watching,
        // because a value we cannot parse is a poor reason to stop looking
        // for the failure that this watchdog exists to catch.
        default:
            log.warn "initialize(): unrecognized watchdog interval '" + watchdogEvery +
                     "'; falling back to every 5 minutes"
            watchdogEvery = "5"
            runEvery5Minutes("watchdog")
            break
    }

    if (watchdogEvery == "never") {
        log.warn "initialize(): the watchdog is disabled, so nothing on the hub " +
                 "will notice a websocket that stops delivering to us"
    }
    else {
        log.info "initialize(): watchdog will check every " + watchdogEvery +
                 " minute(s), reconnecting if nothing has been received for " +
                 staleAfter + " minute(s)"

        // Checking less often than the staleness threshold breaks nothing,
        // but it means a dead socket can go unnoticed for the sum of the two
        // rather than for the threshold alone.
        if ((watchdogEvery as Integer) > staleAfter) {
            log.warn "initialize(): the watchdog interval (" + watchdogEvery +
                     " minute(s)) is longer than the staleness threshold (" + staleAfter +
                     " minute(s)), so a dead socket may go unnoticed for up to " +
                     ((watchdogEvery as Integer) + staleAfter) + " minutes"
        }
    }

    if (logEnable) log.debug "initialize(): ends"
}

void webSocketStatus(String message) {
    // handle error messages and reconnect
    log.info "webSocketStatus(): ${message}"
    if(message.startsWith("failure")) {
        // reconnect in a little bit
        log.warn "Will attempt reconnection in 2 seconds, having received webSocketStatus message: ${message}"
        runIn(2, "connect")
    }
}

void parse(String description) {
    
    def descData = new JsonSlurper().parseText(description)
    String logLevel = descData.level != null ? descData.level : "info"
    String  sourceId = "${descData.id}"

    // Register ourself so that sibling instances of this driver know
    // we're alive.  This happens here as well as in initialize() so
    // that our registration is self-correcting after a re-save of the
    // driver code.
    syslogSiblings["${device.id}"] = device.displayName

    // IMPORTANT LOOP PREVENTION CODE.
    //
    // Anything this section logs comes straight back to us through the very
    // websocket we are reading, so everything we say here is a potential
    // input to ourselves.  Three rules follow.
    //
    // 1. If *this* instance of the Syslog device generated the incoming log
    //    line, DO NOT FORWARD IT.  Otherwise, in debug mode, forwarding a
    //    line logs a line, which we receive and forward, which logs a
    //    line...  (We tell by comparing the device number of the device
    //    that generated the incoming line against our own.)  Strictly this
    //    only bites while we are in debug mode, since with debug off this
    //    section says nothing at all; we squelch unconditionally anyway,
    //    because a device mirroring its own messages is confusing to read
    //    and because it costs nothing.
    //
    // 2. Anything logged in this section MUST be at level debug or trace.
    //    Not merely because anything else would make little sense -- we are
    //    here to mirror other devices' logs, not to talk about ourselves --
    //    but more urgently because the sibling squelch below recognizes
    //    only debug and trace.  An info- or warn-level line emitted per
    //    incoming message would escape it and set two instances amplifying
    //    each other without limit, in debug mode or out of it.  (The "No
    //    log server set" message further down is debug-level for exactly
    //    this reason; connect() carries the warning instead.)
    //
    // 3. A sibling's debug and trace must be squelched IF we are ourselves
    //    in debug mode, and so generating chatter of our own down here.
    //    That mutual case is the only one that amplifies: with just one of
    //    us in debug, the other forwards our chatter once, says nothing
    //    itself, and it ends there.  So when we are not in debug mode it is
    //    safe to forward a sibling's chatter, which is what lets one host's
    //    syslog show the other's Syslog device thinking out loud.
    //
    // A sibling's info and above are never squelched, in either mode.  That
    // is what lets the two hosts' logs show each other's Syslog devices
    // connecting and disconnecting.
    //
    boolean fromUs      = (descData.type == "dev" && sourceId == "${device.id}")
    boolean fromSibling = (descData.type == "dev" && syslogSiblings.containsKey(sourceId))
    boolean chatter     = (logLevel == "debug" || logLevel == "trace")

    // don't log our own messages, we will get into a loop
    if(!fromUs && !(fromSibling && chatter && logEnable)) {
        // Make a note that the websocket has delivered an inbound message to us.
        // This is the only evidence we have that it is alive; a socket
        // that has quietly died looks exactly like a hub with nothing to
        // say, and no "failure" status ever arrives to tell us otherwise.
        state.lastReceived = now()

        if(ip) {
            def priority = facilityMap[logFacility] + priorityMap[logLevel]

            // we get date-space-time but would like ISO8601
            //if (logEnable) log.debug "timezone from hub is ${location.timeZone.toString()}"
            def dateFormat = "yyyy-MM-dd HH:mm:ss.SSS"
            def date = Date.parse(dateFormat, descData.time)
            
            // location timeZone comes from the geolocation of the hub. It's possible it's not set?
            def isoDate = date.format("yyyy-MM-dd'T'HH:mm:ss.SSSXXX", location.timeZone)
            //if (logEnable) log.debug "time we get = ${descData.time}; time we want ${isoDate}"
            
            // jtp10181
            def message = escapeStringHTMLforMsg(descData.msg)

            // made up PROCID or MSGID //TODO find PROCID and MSGID in the API?
            // we fall back to retrieving hostname or IP address here
            // if state.displayHostname, which should have been set
            // in initialize() by setHubDisplayName(), hasn't been set.
            def constructedString = "<${priority}>${syslogVersion} ${isoDate} " +
                        (state.displayHostname ?: location.hubs[0].getDataValue("localIP")) + " " +
                        descData.type + " " + descData.id.toString() + " - - ${message}"

            if (logEnable) {
              log.debug "parse(): sending to " + udptcp + "/" + ip + ":" + port +
                        " -- " + constructedString
            }

            if (udptcp == 'UDP') {
              sendHubCommand(new HubAction(constructedString, Protocol.LAN, [destinationAddress: "${ip}:${port}", type: HubAction.Type.LAN_TYPE_UDPCLIENT, ignoreResponse:true]))
            } else {
              sendHubCommand(new HubAction(constructedString, Protocol.RAW_LAN, [destinationAddress: "${ip}:${port}", type: HubAction.Type.LAN_TYPE_RAW]))
            }

        } else {
            if (logEnable) log.debug "parse(): No syslog server set; nothing forwarded"
        }
    }
}

// =================== exposed subroutines ===============================

// scheduled by us
void disableDebugLogging(){
    device.updateSetting("logEnable",[value:"false",type:"bool"])
    log.warn "debug logging disabled..."
}

// scheduled or called directly by us
void connect() {
    log.info "connect()"

    if (!ip) {
        log.warn "connect(): no syslog server address is set, so nothing will be forwarded"
    }

    state.remove("deliberateDisconnect")
    try {
        interfaces.webSocket.connect("http://localhost:8080/logsocket")
    } catch(e) {
        log.error "connect() caught an error: ${e}"
    }
    if (logEnable) log.debug "end of connect()"
}

// invoked from device page or Maker API
void disconnect() {
    log.info "disconnect()"
    // Mark as deliberate, so the watchdog does not undo this behind
    // our back, otherwise "disconnect" would mean only "disconnect
    // for up to five minutes (or watchdog interval)".
    state.deliberateDisconnect = true
    interfaces.webSocket.close()
}

// invoked from device page or Maker API
void checkConnection() {
    connectionCheck("checkConnection()")
}

// scheduled by us
void watchdog() {
    connectionCheck("watchdog()")
}

// scheduled by us
void postBootCheck() {
    connectionCheck("postBootCheck()")
}

// implements the three calls above
// 
// Reconnect, but only if there is reason to think we need to.  Called
// from initialize() (as "postBootCheck()") a couple of times while
// the hub is settling after a boot, and thereafter by the periodic
// watchdog (as "watchdog()").  It is also available as a command
// (as "checkConnection()") so that the reconnection decision can be
// provoked on demand rather than waited for.
// 
// state.lastReceived of 0 means "nothing since we last (re)initialized",
// which is precisely the condition the post-boot checks are looking for.
//
private void connectionCheck(String callerparam = "connectionCheck()") {
    Long staleMillis = (staleMinutes ?: 10) * 60000
    Long last        = state.lastReceived ?: 0
    Long quietMillis = now() - last

    String caller   = callerparam ?: "connectionCheck()";
    String quietFor = sprintf("%.1f", quietMillis / 60000)
    String lastSeen = last ? quietFor + " minute(s) ago" : "nothing since initialize()"
    String flagged  = state.deliberateDisconnect ? "set" : "unset"

    if (logEnable) {
      log.debug caller + ": last received " +
               lastSeen +
               ", threshold " + (staleMinutes ?: 10) + " minute(s)" +
               ", deliberateDisconnect " + flagged
    }

    if (state.deliberateDisconnect) {
        if (logEnable) log.debug caller + ": we were disconnected on purpose; leaving well alone"
        return
    }

    if (last && quietMillis < staleMillis) {
        if (logEnable) log.debug caller + ": received something " + quietFor + " minute(s) ago; nothing to do"
        return
    }

    // Still here? Need to reconnect.
    if (last) {
        log.warn caller + ": nothing received for " + quietFor + " minute(s); reconnecting"
    } else {
        log.warn caller + ": nothing received since initialize(); reconnecting"
    }
    connect()
}

// =================== private utility routines ==========================

private void setHubDisplayName() { // hub name for outgoing syslog lines

    String name = hostname?.trim() // fetched from Preferences, if available

    if (!name) {
        name = location.hubs[0].getDataValue("localIP")
        log.warn "setHubDisplayName(): no hub hostname is set; using the hub's " +
                 "IP address, " + name
    }

    state.displayHostname = name
}

// ------------------------------------------------------------------
// 2024-08-25 jtp10181
// string sanitizing
private String escapeStringHTMLforMsg(String str) {
    if (str) {
        str = str.replaceAll("&amp;", "&")
        str = str.replaceAll("&lt;", "<")
        str = str.replaceAll("&gt;", ">")
        str = str.replaceAll("&#027;", "'")
        str = str.replaceAll("&#039;", "'") 
        str = str.replaceAll("&apos;", "'")
        str = str.replaceAll("&quot;", '"')
               str = str.replaceAll("&nbsp;", " ")
        //Strip HTML Span Tags
        str = str.replaceAll(/<\/?span.*?>/, "")
    }
    return str
}

