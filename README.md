# hubitatCode

NOTICE: The TCP side of this is still a work-in-progress, specifically working to get rid of the hub log warning "Read timed out", which is ellusive to fix. Please PR this if you have the fix!

Install this by creating a custom device driver. Under device driver set the syslog IP addres, port, UDP/TCP, and optionally set a hostname for the hub.

 *** DO NOT "logEnable" MORE THAN ONE "Syslog" DEVICE AT A TIME, OR YOU
     WILL GET A RECIPROCAL LOOP BETWEEN THEM. ***
(most people will likely use only one remote syslog device anyway ...)

See also https://github.com/rmonk/hubitatSyslogDriver
