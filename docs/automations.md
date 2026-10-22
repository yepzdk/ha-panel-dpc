# Home Assistant automations for the panel

All control goes through the Companion app's notify action. Find the panel's
action name under **Developer tools → Actions → notify.mobile_app_***.

> **Using the [Smart Panel integration](https://github.com/yepzdk/ha-smart-panel)?**
> Use its entities and actions in automations instead (for example
> `smart_panel.navigate`, or the panel's Brightness and Restart entities):
> they target the panel by device, and need neither a notify action nor the
> command format below.

Prerequisite: the panel's Companion app must keep its WebSocket connection
always on (Settings → Companion app → Troubleshooting → Persistent connection
= **Always**). It is what delivers commands without Google Play Services. The
Smart Panel integration sets it when it links a panel; without the integration,
set it by hand on each panel.

The examples use placeholders in angle brackets. Replace them with your own:
`notify.mobile_app_<your_panel>` with the panel's notify action, and
`<your_presence_sensor>`, `<your_alarm_panel>` and `<your_light_sensor>` with
your entity IDs. Keep `dk.yepzdk.hapanel` as it is: it is the panel app's
package name, the same on every panel.

## Wake screen on motion / presence (DPC broadcast)

```yaml
automation:
  - alias: "Panel: wake on presence"
    triggers:
      - trigger: state
        entity_id: binary_sensor.<your_presence_sensor>
        to: "on"
    actions:
      - action: notify.mobile_app_<your_panel>
        data:
          message: command_broadcast_intent
          data:
            intent_package_name: dk.yepzdk.hapanel
            intent_action: dk.yepzdk.hapanel.SCREEN_ON
```

## Screen off while alarm is armed (DPC broadcast)

> **Avoid for now.** After a screen off, the Home Assistant app on the panel
> can confirm the next command too late. Home Assistant then drops the panel's
> connection without telling the app, and no command reaches the panel until
> it restarts; a tap may not wake it either. Tracked in
> [home-assistant/core#176371](https://github.com/home-assistant/core/issues/176371).
> Dim with `SET_BRIGHTNESS` instead.

The Companion app has no screen-off command; the DPC provides one. It covers
the dashboard with a black overlay and keeps the device awake: real sleep also
stops the Companion app's connection, and HA could not wake the panel again.

```yaml
automation:
  - alias: "Panel: screen off when alarm armed"
    triggers:
      - trigger: state
        entity_id: alarm_control_panel.<your_alarm_panel>
        to: armed_away
    actions:
      - action: notify.mobile_app_<your_panel>
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
        entity_id: sensor.<your_light_sensor>
        below: 10
    actions:
      - action: notify.mobile_app_<your_panel>
        data:
          message: command_broadcast_intent
          data:
            intent_package_name: dk.yepzdk.hapanel
            intent_action: dk.yepzdk.hapanel.SET_BRIGHTNESS
            intent_extras: "brightness:30"
  - alias: "Panel: brighten in daylight"
    triggers:
      - trigger: numeric_state
        entity_id: sensor.<your_light_sensor>
        above: 50
    actions:
      - action: notify.mobile_app_<your_panel>
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
      - action: notify.mobile_app_<your_panel>
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
  update_panel_app:
    alias: "Update the panel app"
    sequence:
      - action: notify.mobile_app_<your_panel>
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
  factory_reset_panel:
    alias: "Factory reset the panel"
    sequence:
      - action: notify.mobile_app_<your_panel>
        data:
          message: command_broadcast_intent
          data:
            intent_package_name: dk.yepzdk.hapanel
            intent_action: dk.yepzdk.hapanel.FACTORY_RESET
            intent_extras: "confirm:wipe"
```

The device reboots into the setup wizard, ready for a fresh QR scan.
