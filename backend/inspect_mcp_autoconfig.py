import os
import zipfile
from pathlib import Path
repo = Path(os.environ['USERPROFILE']) / '.m2' / 'repository' / 'org' / 'springframework' / 'ai' / 'spring-ai-mcp-server-spring-boot-autoconfigure' / '1.0.0-M6'
jar_path = repo / 'spring-ai-mcp-server-spring-boot-autoconfigure-1.0.0-M6.jar'
print('Jar:', jar_path)
with zipfile.ZipFile(jar_path, 'r') as jar:
    entries = jar.namelist()
    matches = [name for name in entries if 'MpcServerAutoConfiguration' in name or 'stdio' in name.lower() or 'transport' in name.lower()]
    print('Matched entries:')
    for name in matches:
        print(name)
    print('--- META-INF entries ---')
    for name in entries:
        if name.startswith('META-INF/'):
            if any(sub in name for sub in ['spring.factories', 'EnableAutoConfiguration', 'AutoConfiguration.imports', 'spring/org.springframework.boot.autoconfigure.EnableAutoConfiguration']):
                print(name)
    print('--- META-INF resource contents ---')
    for candidate in ['META-INF/spring.factories', 'META-INF/spring/org.springframework.boot.autoconfigure.EnableAutoConfiguration', 'META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports']:
        if candidate in entries:
            print(f'Content of {candidate}:')
            with jar.open(candidate, 'r') as f:
                print(f.read().decode('utf-8', errors='ignore'))
    print('--- class string search ---')
    import re
    candidate_class = 'org/springframework/ai/autoconfigure/mcp/server/MpcServerAutoConfiguration.class'
    if candidate_class in entries:
        data = jar.read(candidate_class)
        strings = re.findall(rb'[ -~]{4,}', data)
        for keyword in [b'spring.ai.mcp.server', b'stdio', b'type', b'enabled', b'sse-endpoint', b'sse-message-endpoint', b'resourceChangeNotification', b'toolChangeNotification', b'promptChangeNotification', b'sseEndpoint', b'sseMessageEndpoint']:
            found = [s.decode() for s in strings if keyword in s]
            if found:
                print(f'found class string {keyword.decode()}:', found)
