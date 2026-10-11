# Home Assistant automations for the panel

All control goes through the Companion app's notify service. Find the panel's
service name under **Developer tools → Actions → notify.mobile_app_***.

Prerequisite: the panel's Companion app must keep its WebSocket connection
always on (Settings → Companion app → Troubleshooting → Persistent connection
= **Always**). This is the default on the minimal flavor the DPC installs, and
it is what delivers commands without Google Play Services.

Replace `notify.mobile_app_entrance_panel` with your panel's service.

## Wake screen on motion / presence (DPC broadcast)

```yaml
automation:
  - alias: "Panel: wake on presence"
    triggers:
      - trigger: state
        entity_id: binary_sensor.entrance_presence
        to: "on"
    actions:
      - action: notify.mobile_app_entrance_panel
        data:
          message: command_broadcast_intent
          data:
            intent_package_name: dk.yepzdk.hapanel
            intent_action: dk.yepzdk.hapanel.SCREEN_ON
```

## Screen off while alarm is armed (DPC broadcast)

The Companion app has no screen-off command; the DPC provides one. It covers
the dashboard with a black overlay and keeps the device awake: real sleep also
stops the Companion app's connection, and HA could not wake the panel again.

```yaml
automation:
  - alias: "Panel: screen off when alarm armed"
    triggers:
      - trigger: state
        entity_id: alarm_control_panel.home
        to: armed_away
    actions:
      - action: notify.mobile_app_entrance_panel
        data:
          message: command_broadcast_intent
          data:
            intent_package_name: dk.yepzdk.hapanel
            intent_action: dk.yepzdk.hapanel.SCREEN_OFF
```

Wake with `SCREEN_ON` (e.g. on disarm, or on presence as above), or by tapping
the panel. The Companion app's `command_screen_on` does not remove the overlay.
HA cannot see whether the overlay is up — the Interactive sensor stays on — so
track it in a helper if an automation needs to know.

## Dim in a dark environment (DPC broadcast)

Uses the panel's own illuminance sensor (enable it in the Companion app under
Settings → Companion app → Manage sensors), or any room light sensor.

```yaml
automation:
  - alias: "Panel: dim at night"
    triggers:
      - trigger: numeric_state
        entity_id: sensor.entrance_panel_light_sensor
        below: 10
    actions:
      - action: notify.mobile_app_entrance_panel
        data:
          message: command_broadcast_intent
          data:
            intent_package_name: dk.yepzdk.hapanel
            intent_action: dk.yepzdk.hapanel.SET_BRIGHTNESS
            intent_extras: "brightness:30"
  - alias: "Panel: brighten in daylight"
    triggers:
      - trigger: numeric_state
        entity_id: sensor.entrance_panel_light_sensor
        above: 50
    actions:
      - action: notify.mobile_app_entrance_panel
        data:
          message: command_broadcast_intent
          data:
            intent_package_name: dk.yepzdk.hapanel
            intent_action: dk.yepzdk.hapanel.SET_BRIGHTNESS
            intent_extras: "brightness:auto"
```

`brightness:auto` hands control back to the panel's own light sensor; any
number switches automatic brightness off. A brightness set while the screen is
off takes effect when it wakes.

Alternative without the DPC: `command_screen_brightness_level` (value 0–255)
does the same natively, but requires granting the Companion app "Modify system
settings" once by hand on each panel. The DPC broadcast needs no setup.

## Update the HA app on the panel

```yaml
automation:
  - alias: "Panel: update HA app"
    triggers:
      - trigger: time
        at: "04:00:00"
    actions:
      - action: notify.mobile_app_entrance_panel
        data:
          message: command_broadcast_intent
          data:
            intent_package_name: dk.yepzdk.hapanel
            intent_action: dk.yepzdk.hapanel.UPDATE_HA
```

The trampoline re-pins the dashboard automatically after the update installs.

## Update the DPC on the panel

`UPDATE_PANEL` installs the DPC's latest GitHub release; the panel shows the
new version briefly and returns to the dashboard. Run it after publishing a
release rather than on a schedule:

```yaml
script:
  update_entrance_panel_dpc:
    alias: "Update entrance panel DPC"
    sequence:
      - action: notify.mobile_app_entrance_panel
        data:
          message: command_broadcast_intent
          data:
            intent_package_name: dk.yepzdk.hapanel
            intent_action: dk.yepzdk.hapanel.UPDATE_PANEL
```

## Factory reset a panel for re-provisioning

Destructive — wipes the device. The `confirm` extra is mandatory; without it
the command is ignored. Handy as a script (not an automation) you trigger
manually when re-testing provisioning:

```yaml
script:
  reset_entrance_panel:
    alias: "Factory reset entrance panel"
    sequence:
      - action: notify.mobile_app_entrance_panel
        data:
          message: command_broadcast_intent
          data:
            intent_package_name: dk.yepzdk.hapanel
            intent_action: dk.yepzdk.hapanel.FACTORY_RESET
            intent_extras: "confirm:wipe"
```

The device reboots into the setup wizard, ready for a fresh QR scan.
