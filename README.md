# Danish electricity calendar
A personalised, auto-updating [iCalendar](https://en.wikipedia.org/wiki/ICalendar) of Danish electricity prices which you can subscribe to via a [compatible application](https://en.wikipedia.org/wiki/List_of_applications_with_iCalendar_support), e.g. Calendar on macOS.

The `dk.simongray.el.prices` namespace is a micro-library for sourcing the latest Danish electricity prices, while `dk.simongray.el.calendar` contains a small Pedestal web service exposing the calendar itself.

## Setup
Caddy must be installed, along with Clojure and a JVM of course.

### Development
Assuming that the calendar web service is already running in a Clojure REPL:

```shell
# local reverse proxy
caddy run
```

Apple's Calendar app won't like the fact that localhost has a subdomain (`el.localhost`), so the actual calendar subscription will fail for that reason. However, subscribing via `localhost:9876` can be used to verify that the service works as intended.

### Production
Either upload via SFTP or build an uberjar called `el.jar`. The uberjar is created by running the `ci` function in  `dev/src/build.clj`. This .jar file needs to be located in `/opt/el` on the server.

To save on server resources, it's probably best to build `el.jar` on the dev machine and upload it to the server. Running an uberjar (as opposed to running via `/usr/local/bin/clojure -Xserver`, which I did originally) is [much more performant](https://github.com/simongray/el/issues/6).

Copy Systemd units and Caddyfile:

```shell
# from inside the `el` directory
sh install.sh
```

Systemd setup:

```shell
# enable systemd units (just once)
systemctl enable el
systemctl enable caddy
```

Starting the system:

```shell
# start services (as needed)
systemctl start el
systemctl start caddy

# ..or do a manual caddy run (debugging only)
DOMAIN=simongray.dk caddy run
```

## See also
* [Caddy systemd documentation](https://caddyserver.com/docs/running#unit-files).
