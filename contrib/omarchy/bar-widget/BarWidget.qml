import QtQuick
import Quickshell
import Quickshell.Io
import qs.Ui
import qs.Commons

// Shows gillspeakd's state from $XDG_RUNTIME_DIR/gillspeak-state. Hidden while the daemon isn't running.
BarWidget {
  id: root
  moduleName: "local.gillspeak"

  property string daemonState: "off"
  readonly property bool recording: daemonState === "recording"
  readonly property bool processing: daemonState === "processing"

  visible: daemonState !== "off"
  implicitWidth: button.implicitWidth
  implicitHeight: button.implicitHeight

  // The file is rewritten in place, so a read can land on it empty. Only known states are accepted.
  function applyState(raw) {
    var next = (raw || "").trim()
    if (next === "idle" || next === "recording" || next === "processing") daemonState = next
  }

  FileView {
    id: stateFile
    path: Quickshell.env("XDG_RUNTIME_DIR") + "/gillspeak-state"
    watchChanges: true
    printErrors: false
    onLoaded: root.applyState(text())
    onLoadFailed: root.daemonState = "off"
    onFileChanged: reload()
  }

  // Picks the file up again after the daemon restarts and recreates it.
  Timer {
    interval: 5000
    running: true
    repeat: true
    onTriggered: stateFile.reload()
  }

  BarIconButton {
    id: button
    anchors.fill: parent
    bar: root.bar
    text: root.processing ? "󰔟" : "󰍬"
    active: root.recording
    dimmed: !root.recording && !root.processing
    tooltipText: root.recording ? "gillspeak: listening, click to finish"
      : root.processing ? "gillspeak: writing your text"
      : "gillspeak: ready, click to dictate"
    onPressed: function(b) {
      if (b === Qt.LeftButton) root.bar.run("gillspeak toggle")
    }
  }
}
