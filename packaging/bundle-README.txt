FTCSim - FTC robot code simulator (BIOBUZZ 2026-27 field and rules)
====================================================================

START
  Double-click FTCSim.exe. A window opens with the simulator: pick an OpMode, press INIT and START,
  drive with a gamepad or the keyboard. Closing the window ends FTCSim.
  No installation, no Java and no browser tab are needed. The window is an app-mode window of
  Microsoft Edge (included with Windows 10/11); Google Chrome, Brave or Chromium are used when Edge
  is missing.

YOUR OPMODES
  On first start FTCSim creates  <Documents>\FTCSim\autos  and copies the sample BIOBUZZ OpModes
  (samples\autos in this folder) into it. Existing files are never overwritten: edit them, add your
  own .java files there, and press the reload button in the UI (changes are also picked up
  automatically). To use another folder:

      FTCSim.exe "C:\path\to\my\opmodes"
      FTCSim.exe --autos "C:\path\to\my\opmodes"

  An FtcRobotController project can be given with  FTCSim.exe --repo "C:\path\to\project"
  (the project's own libraries are not part of this bundle; only plain FTC SDK code is available).

BATCH / CONSOLE
  FTCSim-cli.exe is the same program with a console window. It prints the log to the console and
  accepts all options. Examples:

      FTCSim-cli.exe --help
      FTCSim-cli.exe --self-test
      FTCSim-cli.exe --autos "C:\path\to\my\opmodes" --batch --report out.json
      FTCSim-cli.exe --no-window --port 8000        (server only, open http://127.0.0.1:8000/ yourself)

WHERE THINGS ARE
  Log file        %LOCALAPPDATA%\FTCSim\ftcsim.log
  Data            %LOCALAPPDATA%\FTCSim\data        (compiled OpModes, browser profile; --data <dir> changes it)
  Robot configs   configs\ in this folder (Biobuzz.json, Decode.json). If this folder is read-only,
                  a copy under %LOCALAPPDATA%\FTCSim\home\configs is used instead.
  OpMode samples  <Documents>\FTCSim\autos

NOTES
  - The simulator listens on port 8000, or another free port when 8000 is taken. Starting FTCSim a
    second time opens a second window with its own port and data.
  - Settings of the UI are stored in the browser profile under the data folder.
  - To remove FTCSim delete this folder and %LOCALAPPDATA%\FTCSim.
