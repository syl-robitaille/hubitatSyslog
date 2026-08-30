# hubitatSyslog

Originally forked from https://github.com/staylorx/hubitatCode/, which
itself was a fork from https://github.com/rmonk/hubitatSyslogDriver,
this is an updated remote syslog device driver for Hubitat home
automation hubs.

The latest version of this driver contains watchdog code to restart
its connection to the Hubitat device's log interface if it finds the
device has been quiet for longer than is typical.

Install this by creating a custom device driver. Configure the syslog
server name or IP addres, port, UDP/TCP, and optionally set a hostname
for the hub.

## Tip

A Rule Machine rule can be created to send a "mark" log message at a
desired interval, shorter than the watchdog timeout.  This way you'll
always know that the remote syslog device is working.
