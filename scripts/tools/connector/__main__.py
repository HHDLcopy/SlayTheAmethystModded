from __future__ import annotations

import sys

if __name__ == "__main__":
    if len(sys.argv) < 2 or sys.argv[1] != "daemon":
        print(
            "Usage: python -m scripts.tools.connector daemon "
            "[--port PORT] [--token TOKEN] [--pid-file PID_FILE]"
        )
        raise SystemExit(1)
    sys.argv.pop(1)
    from scripts.tools.connector.daemon import main

    main()
