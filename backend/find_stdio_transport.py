import os
import zipfile
from pathlib import Path
root = Path(os.environ['USERPROFILE']) / '.m2' / 'repository'
needle = 'StdioServerTransport.class'
for p in root.rglob('*.jar'):
    try:
        with zipfile.ZipFile(p, 'r') as z:
            if needle in z.namelist():
                print(p)
                print([name for name in z.namelist() if needle in name])
    except Exception:
        pass
