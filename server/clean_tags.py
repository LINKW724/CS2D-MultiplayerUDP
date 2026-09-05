import os
import re

files = [
    r'o:\java\games\CS2D-MultiplayerUDP\src\main\java\cs2d\AIControl\BG\TEAM_DEATHMATCHcontrol.java',
    r'o:\java\games\CS2D-MultiplayerUDP\src\main\java\cs2d\AIControl\BG\ZOMBIEcontrol.java'
]

tags_pattern = re.compile(r'\[(新增|修改|重大修改|修复|核心修复|核心|优化|REALISTIC|已修改|新辅助方法|!! 同步客户端逻辑 !!|!! 核心修复 !!|由 .+ 增加到|签名修改|Error Fix|公共 DTO|已移除|新的公共 API|异步执行|自动计算)\]\s*')
blocks_pattern = re.compile(r'//\s*---\s*(?:新增|结束新增|新增：).*?---\s*\n|//\s*---\s*\[新增.*?\]\s*---\s*\n')

for file_path in files:
    with open(file_path, 'r', encoding='utf-8') as f:
        content = f.read()
    
    # Remove tags from text
    content = tags_pattern.sub('', content)
    
    # Remove specific lines like "// --- 新增 Import ---"
    content = blocks_pattern.sub('', content)
    
    # Additional specific removals
    content = content.replace('// --- [REALISTIC 难度专项：蜂群意识共享] ---\n', '')
    content = content.replace('// --- [新增逻辑结束] ---\n', '')
    content = content.replace('// --- VVVV 核心修改 VVVV ---\n', '')
    content = content.replace('// --- ^^^^ 修复结束 ^^^^ ---\n', '')
    content = content.replace('// --- VVVV [核心修复] VVVV ---\n', '')
    content = content.replace('// --- ^^^^ [修复结束] ^^^^ ---\n', '')
    
    with open(file_path, 'w', encoding='utf-8') as f:
        f.write(content)
    
    print(f"Processed {file_path}")
