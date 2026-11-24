# External Intent Control

External recording control is optional and disabled by default. Enable it in the
app's security settings, or explicitly set the managed restriction
`allow_intent_control=true`. An explicit MDM restriction keeps its existing
priority unless the user has enabled the MDM override.

The first launch after this security update resets the old local setting to
disabled once. Earlier versions saved an enabled default, so that value cannot
prove the user opted in. Re-enable the switch to authorize external control.
Later launches preserve that new choice. Explicit MDM authorization is retained.

## Migration

`com.craxiom.networksurvey.services.NetworkSurveyService` is now an internal
service. External `startservice`, `start-foreground-service`, and `bindService`
calls to that component are rejected by Android.

Send an explicit broadcast to
`com.craxiom.networksurvey.SurveyControlReceiver` instead. The actions remain:

- `com.craxiom.networksurvey.START_SURVEY`
- `com.craxiom.networksurvey.STOP_SURVEY`

Replace the application package in the examples for another build variant
(debug builds normally add `.dev`).

```shell
adb shell am broadcast \
  -n com.wireless.signalmeter.new/com.craxiom.networksurvey.SurveyControlReceiver \
  -a com.craxiom.networksurvey.START_SURVEY \
  --ez cellular_file_logging true

adb shell am broadcast \
  -n com.wireless.signalmeter.new/com.craxiom.networksurvey.SurveyControlReceiver \
  -a com.craxiom.networksurvey.STOP_SURVEY
```

The start action accepts only these boolean extras, each defaulting to `false`:

- `cellular_file_logging`
- `phone_state_file_logging`
- `wifi_file_logging`
- `bluetooth_file_logging`
- `gnss_file_logging`
- `cdr_file_logging`

The stop action accepts no extras. Unknown actions do nothing. The gateway never
copies the caller's complete bundle; boot flags and all other extras are ignored.
The external-control preference is checked before dispatch and again by the
internal service. Android foreground-service and location-permission rules still
apply to requests from background automation.

In-app monitoring and recording continue through the private service and its
local binder. System boot auto-start and SIM-change handling retain their existing
receivers and do not require external control to be enabled.
