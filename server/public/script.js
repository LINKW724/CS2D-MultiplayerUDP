// --- 武器与道具数据 (用于UI) ---
// 这些数据只用于在前端显示名称、价格等信息，真正的购买逻辑和价格验证在服务器端。
const WEAPONS = { "Pistols": ["GLOCK18", "USPS", "P250", "FIVESEVEN", "TEC9", "DEAGLE", "R8"], "Shotguns": ["NOVA", "XM1014", "MAG7", "SAWEDOFF"], "SMGs": ["MP9", "MAC10", "MP7", "UMP45", "P90", "BIZON"], "Rifles": ["AK47", "M4A4", "M4A1S", "FAMAS", "GALIL", "AUG", "SG553"], "Snipers": ["AWP", "SSG08"], "Machine_Guns": ["NEGEV", "M249"] };
const WEAPON_DATA = {
    AK47: { cost: 2700, name: "AK-47" }, M4A4: { cost: 3100, name: "M4A4" }, M4A1S: { cost: 2900, name: "M4A1-S" }, FAMAS: { cost: 2050, name: "FAMAS" }, GALIL: { cost: 1800, name: "Galil AR" }, AUG: { cost: 3300, name: "AUG" }, SG553: { cost: 3000, name: "SG 553" },
    AWP: { cost: 4750, name: "AWP" }, SSG08: { cost: 1700, name: "SSG 08" },
    MP9: { cost: 1250, name: "MP9" }, MAC10: { cost: 1050, name: "MAC-10" }, MP7: { cost: 1500, name: "MP7" }, UMP45: { cost: 1200, name: "UMP-45" }, P90: { cost: 2350, name: "P90" }, BIZON: { cost: 1400, name: "PP-Bizon" },
    NOVA: { cost: 1050, name: "Nova" }, XM1014: { cost: 2000, name: "XM1014" }, MAG7: { cost: 1300, name: "MAG-7" }, SAWEDOFF: { cost: 1100, name: "Sawed-Off" },
    NEGEV: { cost: 1700, name: "Negev" }, M249: { cost: 5200, name: "M249" },
    GLOCK18: { cost: 200, name: "Glock-18" }, USPS: { cost: 200, name: "USP-S" }, P250: { cost: 300, name: "P250" }, FIVESEVEN: { cost: 500, name: "Five-SeveN" }, TEC9: { cost: 500, name: "Tec-9" }, DEAGLE: { cost: 700, name: "Desert Eagle" }, R8: { cost: 600, name: "R8 Revolver" }
};
const ITEMS = {
    grenades: [
        { key: 'HE_GRENADE', name: '高爆手雷', cost: 300, maxQuantity: 1 }, { key: 'FLASHBANG', name: '闪光弹', cost: 200, maxQuantity: 2 }, { key: 'SMOKE_GRENADE', name: '烟雾弹', cost: 300, maxQuantity: 1 },
        { key: 'MOLOTOV', name: '燃烧瓶 (T)', cost: 600, team: 'T', maxQuantity: 1 }, { key: 'INCENDIARY', name: '燃烧弹 (CT)', cost: 600, team: 'CT', maxQuantity: 1 }, { key: 'DECOY', name: '诱饵弹', cost: 50, maxQuantity: 1 },
    ],
    gear: [
        { key: 'KEVLAR', name: '防弹衣', cost: 650 }, { key: 'KEVLAR_HELMET', name: '防弹衣+头盔', cost: 1000 },
        { key: 'DEFUSE_KIT', name: '拆弹器', cost: 400, team: 'CT' }
    ]
};
const DEMOLITION_MAX_ROUNDS = 24;
const DEMO_PLANT_TIME_MS = 3000;
const DEMO_DEFUSE_TIME_MS = 10000;
const DEMO_DEFUSE_WITH_KIT_TIME_MS = 5000;

// --- 核心游戏变量 ---
const canvas = document.getElementById('gameCanvas'), ctx = canvas.getContext('2d');
canvas.width = 1600; canvas.height = 900;
const PLAYER_SIZE = 24;
let socket; // WebSocket连接对象
let myPlayerId = null; // 本地玩家的ID，由服务器分配
let latestGameState = null; // 从服务器接收到的最新游戏状态，是所有渲染的依据
let gameRunning = false; // 游戏主循环是否正在运行
let animationFrameId; // 用于取消 requestAnimationFrame
let playerName = "Player"; // 玩家名字
const keysDown = new Set(); // 存储当前按下的按键 (例如 'W', 'A')
const mousePos = { x: 0, y: 0 }; // 鼠标在Canvas上的坐标
let isShooting = false; // 玩家是否正在射击
let lastUpdateTime = 0;
let tps = 120; // 目标TPS，用于控制向服务器发送输入的频率
let isInteracting = false; // E键交互状态 (安包/拆包)

// --- 地图与镜头变量 ---
let mapWidth = 1600, mapHeight = 900; // 地图尺寸，会从服务器同步
let cameraX = 0, cameraY = 0; // 镜头左上角在世界坐标系中的位置
let cameraMode = 'follow'; // 镜头模式: 'follow' (跟随玩家) 或 'full' (上帝视角)

// --- UI 元素引用 (方便后续操作) ---
const lobby = document.getElementById('lobby'), gameContainer = document.getElementById('game-container'), connStatusEl = document.getElementById('connection-status'), lobbyContentEl = document.getElementById('lobby-content'), tdmWeaponSelector = document.getElementById('tdm-weapon-selector'), scoreboard = document.getElementById('scoreboard'), nameSelectionEl = document.getElementById('name-selection'), buyMenu = document.getElementById('buy-menu');

// --- 计分板和延迟计算变量 ---
let showScoreboard = false, ping = 0, pingStartTime = 0, showBuyMenu = false;

// --- 视野/战争迷雾系统变量 ---
let fovPoints = []; // 存储视野多边形的顶点
let lastPlayerPos = { x: -1, y: -1 }, lastMousePos = { x: -1, y: -1 }; // 用于判断是否需要重新计算视野

// --- 声音系统 ---
const audioContext = new (window.AudioContext || window.webkitAudioContext)(); // Web Audio API 上下文
const sounds = {}; // 存储已加载的声音Buffer
const SOUND_PATH = './sounds/'; // 声音文件路径 (假设在public/sounds/下)
const MAX_SOUND_DISTANCE = 800; // 声音能传播的最大距离

// 预加载所有可能用到的声音文件
function preloadSounds() {
    const allWeaponNames = Object.values(WEAPONS).flat();
    const otherSounds = ["headshot", "kill", "buy", "plant", "defuse", "受击1", "受击2", "受击3", "受击4", "受击5", "受击6", "受击7"];
    let soundUrls = [];
    allWeaponNames.forEach(weaponName => {
        soundUrls.push({ key: `${weaponName}_fire`, url: `${SOUND_PATH}${weaponName}_fire.wav` });
        soundUrls.push({ key: `${weaponName}_reload`, url: `${SOUND_PATH}${weaponName}_reload.wav` });
    });
    otherSounds.forEach(soundName => soundUrls.push({ key: soundName, url: `${SOUND_PATH}${soundName}.wav` }));
    soundUrls.forEach(({key, url}) => {
        // 异步获取声音文件并解码
        fetch(url).then(r => r.ok ? r.arrayBuffer() : Promise.reject()).then(a => audioContext.decodeAudioData(a)).then(b => sounds[key] = b).catch(e => console.error(`无法加载声音: ${url}`, e));
    });
}

// 播放声音
function playSound(soundKey, soundPos) {
    if (!sounds[soundKey]) return; // 如果声音未加载则不播放
    const source = audioContext.createBufferSource(); source.buffer = sounds[soundKey];
    const gainNode = audioContext.createGain(); // 创建音量控制器

    // 如果声音有位置 (3D音效)
    if (soundPos && latestGameState) {
        const me = latestGameState.players.find(p => p.id === myPlayerId);
        if (!me && myPlayerId) return;
        const listenerPos = me ? {x: me.x, y: me.y} : {x: 800, y: 450}; // 监听者位置 (即玩家自己)
        const distance = Math.sqrt((listenerPos.x - soundPos.x)**2 + (listenerPos.y - soundPos.y)**2);
        if (distance > MAX_SOUND_DISTANCE) return; // 如果太远就听不见
        // 根据距离衰减音量
        gainNode.gain.setValueAtTime(Math.max(0, 1 - (distance / MAX_SOUND_DISTANCE)) * 0.5, audioContext.currentTime);
    } else { // 2D音效 (如UI点击、爆头提示)
        gainNode.gain.setValueAtTime(0.7, audioContext.currentTime);
    }
    source.connect(gainNode).connect(audioContext.destination);
    source.start(0);
}

// --- UI 生成函数 ---
function createBuyMenu() {
    const buyMenuContent = document.getElementById('buy-menu-content');
    buyMenuContent.innerHTML = `
        <div>
            <h3 class="text-xl font-bold text-yellow-300 mb-2">地上武器 (点击拾取)</h3>
            <div id="ground-weapons" class="grid grid-cols-2 gap-2 overflow-y-auto max-h-24 bg-gray-900 p-2 rounded"></div>
        </div>
        <div><h3 class="text-2xl font-bold text-cyan-300 mb-2 mt-4">手枪</h3><div id="buy-pistols" class="weapon-grid"></div></div>
        <div><h3 class="text-2xl font-bold text-cyan-300 mb-2 mt-4">冲锋枪</h3><div id="buy-smgs" class="weapon-grid"></div></div>
        <div><h3 class="text-2xl font-bold text-cyan-300 mb-2 mt-4">步枪</h3><div id="buy-rifles" class="weapon-grid"></div></div>
        <div><h3 class="text-2xl font-bold text-cyan-300 mb-2 mt-4">重型武器</h3><div id="buy-heavies" class="weapon-grid"></div></div>
        <div class="flex gap-8 mt-6">
            <div class="flex-1">
                <h3 class="text-2xl font-bold text-cyan-300 mb-2">护甲</h3>
                <div id="buy-armor" class="grid grid-cols-1 gap-4"></div>
            </div>
            <div class="flex-1">
                <h3 class="text-2xl font-bold text-cyan-300 mb-2">手雷</h3>
                <div id="buy-grenades" class="grid grid-cols-2 gap-4"></div>
            </div>
            <div class="flex-1">
                <h3 class="text-2xl font-bold text-cyan-300 mb-2">装备</h3>
                <div id="buy-equipment" class="grid grid-cols-1 gap-4"></div>
            </div>
        </div>
    `;

    const allItems = { ...WEAPON_DATA, ...Object.fromEntries(ITEMS.grenades.map(i => [i.key, i])), ...Object.fromEntries(ITEMS.gear.map(i => [i.key, i])) };

    const generateButtons = (category, containerId) => {
        const container = document.getElementById(containerId);
        container.innerHTML = category.map(itemKey => {
            const item = allItems[itemKey];
            const displayName = item.name || itemKey;
            return `
                <div class="relative">
                    <button id="buy-btn-${itemKey}" class="buy-btn text-left w-full">
                        <p class="font-bold">${displayName}</p>
                        <p class="text-green-400">$${item.cost}</p>
                    </button>
                    <button id="undo-btn-${itemKey}" class="buy-btn text-left w-full" style="display: none;">
                        <p class="font-bold text-yellow-400">撤销: ${displayName}</p>
                        <p class="text-red-400">+$${item.cost}</p>
                    </button>
                </div>
            `;
        }).join('');
    };

    generateButtons(WEAPONS.Pistols, 'buy-pistols');
    generateButtons(WEAPONS.SMGs, 'buy-smgs');
    generateButtons(WEAPONS.Rifles, 'buy-rifles');
    const heavies = [...WEAPONS.Shotguns, ...WEAPONS.Snipers, ...WEAPONS.Machine_Guns];
    generateButtons(heavies, 'buy-heavies');
    generateButtons(ITEMS.gear.filter(i => i.key.includes('KEVLAR')).map(i => i.key), 'buy-armor');
    generateButtons(ITEMS.grenades.map(i => i.key), 'buy-grenades');
    generateButtons(ITEMS.gear.filter(i => !i.key.includes('KEVLAR')).map(i => i.key), 'buy-equipment');

    // 为所有按钮一次性添加事件监听器
    Object.keys(allItems).forEach(itemKey => {
        const buyBtn = document.getElementById(`buy-btn-${itemKey}`);
        const undoBtn = document.getElementById(`undo-btn-${itemKey}`);

        if (buyBtn) {
            buyBtn.onclick = () => {
                // 点击购买按钮后，向服务器发送购买请求
                if (WEAPON_DATA[itemKey]) {
                    socket.send(JSON.stringify({ type: 'chooseWeapon', weapon: itemKey }));
                } else {
                    socket.send(JSON.stringify({ type: 'buyItem', item: itemKey }));
                }
            };
        }
        if (undoBtn) {
            undoBtn.onclick = () => socket.send(JSON.stringify({ type: 'undoPurchase', item: itemKey }));
        }
    });
}

// 根据服务器发来的最新游戏状态，更新购买菜单的UI
function updateBuyMenu() {
    if (!latestGameState || !showBuyMenu) return;
    const me = latestGameState.players.find(p => p.id === myPlayerId);
    if (!me) return;

    const allItems = { ...WEAPON_DATA, ...Object.fromEntries(ITEMS.grenades.map(i => [i.key, i])), ...Object.fromEntries(ITEMS.gear.map(i => [i.key, i])) };

    // 更新所有购买/撤销按钮的状态 (是否可点击、是否显示)
    Object.keys(allItems).forEach(itemKey => {
        const buyBtn = document.getElementById(`buy-btn-${itemKey}`);
        const undoBtn = document.getElementById(`undo-btn-${itemKey}`);
        if (!buyBtn || !undoBtn) return;

        // 检查这个物品是否是本回合购买的
        const wasBoughtThisRound = me.itemsBoughtThisFreezeTime && me.itemsBoughtThisFreezeTime.includes(itemKey);
        // 检查玩家是否正持有此武器，防止丢弃后再撤销
        const isHoldingThisWeapon = me.weaponKey === itemKey;
        const canUndo = wasBoughtThisRound && (WEAPON_DATA[itemKey] ? isHoldingThisWeapon : true);

        buyBtn.style.display = canUndo ? 'none' : 'block';
        undoBtn.style.display = canUndo ? 'block' : 'none';

        if (!canUndo) {
            let disabled = me.money < allItems[itemKey].cost; // 钱不够则禁用
            // ... (其他禁用逻辑，如护甲已满、手雷已满等)
            const itemInfo = [...ITEMS.grenades, ...ITEMS.gear].find(i => i.key === itemKey);
            if(itemInfo && itemInfo.team && itemInfo.team !== me.team) {
                buyBtn.style.display = 'none'; // 隐藏非本阵营的道具
            }
            if (itemKey === 'KEVLAR' || itemKey === 'KEVLAR_HELMET') disabled = disabled || me.armorValue > 0;
            if (itemKey === 'DEFUSE_KIT') disabled = disabled || me.hasDefuseKit;
            const ownedGrenade = me.equipment.find(e => e.name === itemKey);
            if (ownedGrenade) {
                const grenadeInfo = ITEMS.grenades.find(g => g.key === itemKey);
                if (grenadeInfo && ownedGrenade.count >= grenadeInfo.maxQuantity) {
                    disabled = true;
                }
            }
            buyBtn.disabled = disabled;
        }
    });

    // 更新附近可拾取的武器列表
    const groundWeaponsContainer = document.getElementById('ground-weapons');
    groundWeaponsContainer.innerHTML = '';

    // 过滤出在玩家150像素范围内的掉落武器
    const nearbyWeapons = latestGameState.droppedItems.filter(item =>
        !item.isBomb && (Math.sqrt((item.x - me.x)**2 + (item.y - me.y)**2) < 150)
    );

    if (nearbyWeapons.length > 0) {
        nearbyWeapons.forEach(weapon => {
            const weaponButton = document.createElement('button');
            weaponButton.className = 'buy-btn text-center w-full';
            weaponButton.textContent = WEAPON_DATA[weapon.name]?.name || weapon.name;
            weaponButton.onclick = () => {
                // 向服务器发送拾取武器的请求
                console.log(`[前端日志] 尝试拾取武器: ${weapon.name} (ID: ${weapon.id})`);
                socket.send(JSON.stringify({ type: 'pickupWeapon', itemId: weapon.id }));
                showBuyMenu = false; // 拾取后自动关闭菜单
                buyMenu.style.display = 'none';
            };
            groundWeaponsContainer.appendChild(weaponButton);
        });
    } else {
        groundWeaponsContainer.innerHTML = `<p class="text-gray-500 text-sm col-span-2 text-center">附近没有可拾取的武器</p>`;
    }
}

// --- 网络与核心逻辑 ---

// 连接到WebSocket服务器
function connect() {
    // 动态构建WebSocket地址
    const wsUrl = `${window.location.protocol === 'https:' ? 'wss:' : 'ws:'}//${window.location.hostname}:14726`;
    socket = new WebSocket(wsUrl);
    // 连接成功时
    socket.onopen = () => { connStatusEl.textContent = "连接成功!"; setInterval(() => { pingStartTime = Date.now(); socket.send(JSON.stringify({ type: 'ping' })); }, 2000); };

    // **核心: 接收到服务器消息时**
    socket.onmessage = (event) => {
        const msg = JSON.parse(event.data);
        // 如果是游戏状态更新消息
        if (msg.type === 'gameState') {
            const oldPhase = latestGameState ? latestGameState.roundPhase : null;
            // 将本地的最新游戏状态更新为服务器发来的数据
            // 这是整个客户端渲染的驱动力
            latestGameState = msg;

            // ... (其他逻辑，如同步地图尺寸、处理声音事件等)
            mapWidth = latestGameState.width || 1600;
            mapHeight = latestGameState.height || 900;

            if (oldPhase === 'FREEZE_TIME' && msg.roundPhase === 'IN_PROGRESS' && showBuyMenu) {
                showBuyMenu = false;
                buyMenu.style.display = 'none';
            }

            if (msg.soundEvents) msg.soundEvents.forEach(e => playSound(`${e.weapon}_${e.type.toLowerCase()}`, { x: e.x, y: e.y }));
            if (msg.privateSoundEvents) msg.privateSoundEvents.forEach(e => { if (e.recipientId === myPlayerId) playSound(e.soundName); });

        } else if (msg.type === 'initialInfo') handleInitialInfo(msg); // 处理初始连接信息
        else if (msg.type === 'pong') ping = Date.now() - pingStartTime; // 计算延迟
    };

    // --- 修改开始 ---
    // 连接关闭时
    socket.onclose = () => {
        // 如果连接从未成功建立（myPlayerId 还是 null），则显示更具体的错误
        if (!myPlayerId) {
            connStatusEl.innerHTML = `
                <p class="text-lg font-bold text-red-500">连接被服务器关闭</p>
                <p class="text-sm text-yellow-300 mt-2">
                    <b>诊断:</b> 客户端 (浏览器) 尝试使用 <strong>WebSocket</strong> 协议连接, 但服务器可能不是一个WebSocket服务器。
                    如果您已将服务器修改为纯 <strong>UDP</strong> 模式，浏览器将无法直接连接。这是所有现代浏览器的安全限制。
                    <br><br>
                    <b>解决方案:</b> 请将您的Java服务器程序恢复到使用 <strong>WebSocket</strong> 的版本。
                </p>
            `;
        } else {
            connStatusEl.textContent = "连接丢失! 请刷新页面。";
        }

        if (gameRunning) {
            stopGameLoop();
            document.getElementById('game-over-title').textContent = "已断开连接";
            document.getElementById('game-over').style.display = 'flex';
        }
    };
    // 发生错误时
    socket.onerror = (error) => {
        console.error("WebSocket 错误:", error);
        connStatusEl.textContent = "网络错误，无法建立连接。请检查服务器是否正在运行。";
    };
    // --- 修改结束 ---
}

// 处理服务器发来的初始信息
function handleInitialInfo(msg) {
    myPlayerId = msg.playerId; // 保存自己的ID
    connStatusEl.classList.add('hidden');
    nameSelectionEl.classList.remove('hidden'); nameSelectionEl.classList.add('flex');
    const nameInput = document.getElementById('name-input');
    const finalizeName = (name) => {
        playerName = name.trim() === '' ? 'Player' : name.trim();
        nameSelectionEl.classList.add('hidden');
        lobbyContentEl.classList.remove('hidden');
        showGameModeSelection(msg.mode);
    };
    nameInput.addEventListener('keydown', (e) => { if (e.key === 'Enter') finalizeName(nameInput.value); });
    document.getElementById('random-name-btn').addEventListener('click', () => {
        const adjectives = ["Swift", "Silent", "Deadly", "Phantom", "Shadow", "Crimson", "Azure", "Iron", "Golden"];
        const nouns = ["Strike", "Blade", "Fury", "Reaper", "Ghost", "Hawk", "Wolf", "Serpent", "Storm"];
        const randomName = `${adjectives[Math.floor(Math.random() * adjectives.length)]}${nouns[Math.floor(Math.random() * nouns.length)]}${Math.floor(Math.random() * 100)}`;
        nameInput.value = randomName; finalizeName(randomName);
    });
    document.querySelectorAll('.name-btn[data-name]').forEach(btn => btn.addEventListener('click', () => {
        const name = btn.getAttribute('data-name');
        nameInput.value = name; finalizeName(name);
    }));
}

function showGameModeSelection(mode) {
    const lobbyTitle = document.getElementById('lobby-title');
    const lobbyChoices = document.getElementById('lobby-choices');
    if (mode === 'TEAM_DEATHMATCH' || mode === 'DEMOLITION') {
        lobbyTitle.textContent = '选择你的队伍';
        lobbyChoices.innerHTML = `<div class="flex justify-center gap-6 mt-8"> <button data-selection="CT" class="join-btn px-8 py-4 bg-blue-600 text-white font-bold rounded-lg text-2xl">反恐精英</button> <button data-selection="T" class="join-btn px-8 py-4 bg-red-600 text-white font-bold rounded-lg text-2xl">恐怖分子</button> </div> <div class="text-center mt-6"> <button id="spectate-btn" class="join-btn px-6 py-3 bg-gray-600 text-white font-bold rounded-lg text-xl">观战</button> </div>`;
        document.querySelectorAll('.join-btn[data-selection]').forEach(btn => {
            btn.onclick = () => {
                sendJoinRequest(btn.dataset.selection);
                if (mode === 'TEAM_DEATHMATCH') showInitialWeaponSelection();
            };
        });
        document.getElementById('spectate-btn').onclick = () => {
            myPlayerId = null; lobby.classList.add('hidden'); gameContainer.classList.remove('hidden');
            setupInputListeners(); if (!gameRunning) startGameLoop();
        };
    } else { // ZOMBIE_MODE
        lobbyTitle.textContent = '选择你的初始武器';
        createWeaponGrid('lobby-choices', (weaponKey) => sendJoinRequest(weaponKey));
    }
}

// 向服务器发送加入游戏请求
function sendJoinRequest(selection) {
    socket.send(JSON.stringify({ type: 'joinGame', name: playerName, selection }));
    lobby.classList.add('hidden'); gameContainer.classList.remove('hidden');
    setupInputListeners(); // 设置输入监听
    if (!gameRunning) startGameLoop(); // 启动游戏循环
}

function createWeaponGrid(containerId, onWeaponSelect) {
    const gridContainer = document.getElementById(containerId);
    if (!gridContainer) return;
    gridContainer.innerHTML = ''; // Clear previous content

    Object.entries(WEAPONS).forEach(([category, weapons]) => {
        if (weapons.length > 0) {
            const categoryTitle = document.createElement('h3');
            categoryTitle.className = 'text-2xl font-bold text-cyan-300 mb-2 mt-4 first:mt-0';
            categoryTitle.textContent = category.replace(/_/g, ' ');
            gridContainer.appendChild(categoryTitle);

            const categoryGrid = document.createElement('div');
            categoryGrid.className = 'weapon-grid';

            weapons.forEach(weaponKey => {
                const btn = document.createElement('button');
                btn.className = 'buy-btn p-4 text-center'; // Use buy-btn for consistent styling
                btn.textContent = WEAPON_DATA[weaponKey]?.name || weaponKey;
                btn.onclick = () => onWeaponSelect(weaponKey);
                categoryGrid.appendChild(btn);
            });
            gridContainer.appendChild(categoryGrid);
        }
    });
}


function showInitialWeaponSelection() {
    document.getElementById('weapon-selector-title').textContent = "选择你的初始武器";
    tdmWeaponSelector.style.display = 'flex';
    createWeaponGrid('tdm-weapon-grid', (weaponKey) => {
        socket.send(JSON.stringify({ type: 'chooseWeapon', weapon: weaponKey }));
        tdmWeaponSelector.style.display = 'none';
    });
}

function openInGameWeaponMenu() {
    document.getElementById('weapon-selector-title').textContent = "选择下一条命的武器";
    tdmWeaponSelector.style.display = 'flex';
    createWeaponGrid('tdm-weapon-grid', (weaponKey) => {
        socket.send(JSON.stringify({ type: 'selectNextWeapon', weapon: weaponKey }));
        tdmWeaponSelector.style.display = 'none';
    });
}

// 启动游戏主循环
function startGameLoop() { if (gameRunning) return; gameRunning = true; lastUpdateTime = performance.now(); gameLoop(); }
function stopGameLoop() { gameRunning = false; if (animationFrameId) cancelAnimationFrame(animationFrameId); }

// 游戏主循环
function gameLoop(currentTime) {
    if (!gameRunning) return;
    animationFrameId = requestAnimationFrame(gameLoop); // 请求下一帧
    const elapsed = currentTime - lastUpdateTime;
    const tickInterval = 1000 / tps; // 每隔多久发送一次输入
    if (elapsed >= tickInterval) {
        lastUpdateTime = currentTime - (elapsed % tickInterval);
        sendInput(); // 发送输入到服务器
        draw(); // 绘制游戏画面
    }
}

// **核心: 发送玩家输入到服务器**
function sendInput() {
    if (!socket || socket.readyState !== WebSocket.OPEN || !myPlayerId || !latestGameState) return;
    const me = latestGameState.players.find(p => p.id === myPlayerId);
    if (!me || me.health <= 0) isShooting = false; // 死亡时不能射击

    // ... (计算鼠标在世界坐标系中的位置)
    let mouseWorldX, mouseWorldY;
    if (cameraMode === 'follow') {
        mouseWorldX = mousePos.x + cameraX;
        mouseWorldY = mousePos.y + cameraY;
    } else {
        const scale = Math.min(canvas.width / mapWidth, canvas.height / mapHeight);
        const offsetX = (canvas.width - mapWidth * scale) / 2;
        const offsetY = (canvas.height - mapHeight * scale) / 2;
        mouseWorldX = (mousePos.x - offsetX) / scale;
        mouseWorldY = (mousePos.y - offsetY) / scale;
    }

    // 计算瞄准角度
    const angle = me ? Math.atan2(mouseWorldY - me.y, mouseWorldX - me.x) : 0;

    // 将所有输入打包成JSON，通过WebSocket发送
    socket.send(JSON.stringify({
        type: 'playerInput',
        angle: angle,
        shooting: isShooting,
        keys: Array.from(keysDown) // 将Set转为Array
    }));
}

// --- 渲染逻辑 ---

function isPointInPolygon(point, polygon) {
    if (!polygon || polygon.length === 0) return false;
    let x = point.x, y = point.y, inside = false;
    for (let i = 0, j = polygon.length - 1; i < polygon.length; j = i++) {
        let xi = polygon[i].x, yi = polygon[i].y, xj = polygon[j].x, yj = polygon[j].y;
        if (((yi > y) !== (yj > y)) && (x < (xj - xi) * (y - yi) / (yj - yi) + xi)) inside = !inside;
    }
    return inside;
}
function distToSegmentSquared(p, v, w) {
    const l2 = (v.x - w.x) ** 2 + (v.y - w.y) ** 2;
    if (l2 === 0) return (p.x - v.x) ** 2 + (p.y - v.y) ** 2;
    let t = Math.max(0, Math.min(1, ((p.x - v.x) * (w.x - v.x) + (p.y - v.y) * (w.y - v.y)) / l2));
    const closestX = v.x + t * (w.x - v.x), closestY = v.y + t * (w.y - v.y);
    return (p.x - closestX) ** 2 + (p.y - closestY) ** 2;
}
function isCircleVisibleInPolygon(circle, polygon) {
    if (!polygon || polygon.length === 0) return false;
    if (isPointInPolygon({ x: circle.x, y: circle.y }, polygon)) return true;
    const radiusSq = circle.radius ** 2;
    for (let i = 0, j = polygon.length - 1; i < polygon.length; j = i++) {
        if (distToSegmentSquared({ x: circle.x, y: circle.y }, polygon[j], polygon[i]) < radiusSq) return true;
    }
    return false;
}
function getLineIntersection(p1, p2, p3, p4) {
    const den = (p1.x - p2.x) * (p3.y - p4.y) - (p1.y - p2.y) * (p3.x - p4.x);
    if (den === 0) return null;
    const t = ((p1.x - p3.x) * (p3.y - p4.y) - (p1.y - p3.y) * (p3.x - p4.x)) / den;
    const u = -((p1.x - p2.x) * (p1.y - p3.y) - (p1.y - p2.y) * (p1.x - p3.x)) / den;
    if (t > 0 && t < 1 && u > 0 && u < 1) {
        return { x: p1.x + t * (p2.x - p1.x), y: p1.y + t * (p2.y - p1.y) };
    }
    return null;
}
function calculateFOV(playerPos, obstacles) {
    const uniquePoints = [];
    obstacles.forEach(obs => {
        if (obs.type === 'RECTANGLE') uniquePoints.push({ x: obs.x, y: obs.y }, { x: obs.x + obs.w, y: obs.y }, { x: obs.x, y: obs.y + obs.h }, { x: obs.x + obs.w, y: obs.y + obs.h });
        else if (obs.type === 'POLYGON' && obs.xPoints) { for(let i = 0; i < obs.xPoints.length; i++) uniquePoints.push({ x: obs.xPoints[i], y: obs.yPoints[i] }); }
        else if (obs.type === 'ELLIPSE') {
            const numSegments = 16, centerX = obs.x + obs.w / 2, centerY = obs.y + obs.h / 2, rX = obs.w / 2, rY = obs.h / 2;
            for (let i = 0; i < numSegments; i++) {
                const angle = (i / numSegments) * 2 * Math.PI;
                uniquePoints.push({ x: centerX + rX * Math.cos(angle), y: centerY + rY * Math.sin(angle) });
            }
        }
    });
    const rays = [];
    uniquePoints.forEach(point => {
        const angle = Math.atan2(point.y - playerPos.y, point.x - playerPos.x);
        rays.push({ angle: angle - 1e-4 }, { angle }, { angle: angle + 1e-4 });
    });
    rays.sort((a, b) => a.angle - b.angle);
    return rays.map(ray => {
        const rayEnd = { x: playerPos.x + 4000 * Math.cos(ray.angle), y: playerPos.y + 4000 * Math.sin(ray.angle) };
        let closestHit = rayEnd, minDistanceSq = Infinity;
        obstacles.forEach(obs => {
            let lines = [];
            if (obs.type === 'RECTANGLE') lines = [{p1:{x:obs.x,y:obs.y},p2:{x:obs.x+obs.w,y:obs.y}},{p1:{x:obs.x+obs.w,y:obs.y},p2:{x:obs.x+obs.w,y:obs.y+obs.h}},{p1:{x:obs.x+obs.w,y:obs.y+obs.h},p2:{x:obs.x,y:obs.y+obs.h}},{p1:{x:obs.x,y:obs.y+obs.h},p2:{x:obs.x,y:obs.y}}];
            else if (obs.type === 'POLYGON' && obs.xPoints) { for (let i = 0; i < obs.xPoints.length; i++) lines.push({p1:{x:obs.xPoints[i],y:obs.yPoints[i]},p2:{x:obs.xPoints[(i+1)%obs.xPoints.length],y:obs.yPoints[(i+1)%obs.xPoints.length]}}); }
            else if (obs.type === 'ELLIPSE') {
                const ns=16, cx=obs.x+obs.w/2, cy=obs.y+obs.h/2, rx=obs.w/2, ry=obs.h/2;
                for (let i=0;i<ns;i++) { const a1=(i/ns)*2*Math.PI,a2=((i+1)/ns)*2*Math.PI; lines.push({p1:{x:cx+rx*Math.cos(a1),y:cy+ry*Math.sin(a1)},p2:{x:cx+rx*Math.cos(a2),y:cy+ry*Math.sin(a2)}}); }
            }
            lines.forEach(line => {
                const hit = getLineIntersection(playerPos, rayEnd, line.p1, line.p2);
                if (hit) { const dSq = (hit.x - playerPos.x)**2 + (hit.y - playerPos.y)**2; if (dSq < minDistanceSq) { minDistanceSq = dSq; closestHit = hit; } }
            });
        });
        return closestHit;
    });
}
function calculateFOVIfNeeded() {
    if(!latestGameState) return;
    const me = latestGameState.players.find(p => p.id === myPlayerId);
    if (!me || me.health <= 0) { fovPoints = []; return; }

    const mouseWorldX = (cameraMode === 'follow') ? mousePos.x + cameraX : (mousePos.x - (canvas.width - mapWidth * Math.min(canvas.width / mapWidth, canvas.height / mapHeight)) / 2) / Math.min(canvas.width / mapWidth, canvas.height / mapHeight);
    const mouseWorldY = (cameraMode === 'follow') ? mousePos.y + cameraY : (mousePos.y - (canvas.height - mapHeight * Math.min(canvas.width / mapWidth, canvas.height / mapHeight)) / 2) / Math.min(canvas.width / mapWidth, canvas.height / mapHeight);

    if (me.x !== lastPlayerPos.x || me.y !== lastPlayerPos.y || mouseWorldX !== lastMousePos.x || mouseWorldY !== lastMousePos.y) {
        fovPoints = calculateFOV({ x: me.x, y: me.y }, latestGameState.obstacles);
        lastPlayerPos = { x: me.x, y: me.y };
        lastMousePos = { x: mouseWorldX, y: mouseWorldY };
    }
}

// **核心: 绘制函数**
function draw() {
    ctx.clearRect(0, 0, canvas.width, canvas.height);
    if (!latestGameState) return; // 如果没有收到任何游戏状态，则不绘制

    const me = latestGameState.players.find(p => p.id === myPlayerId);
    const isSpectator = !myPlayerId, isDead = me && me.health <= 0;

    ctx.save(); // 保存当前绘图状态

    // --- 镜头与视口逻辑 ---
    // 根据镜头模式（跟随或上帝）调整画布的变换
    if (cameraMode === 'follow') {
        if (me && me.isAlive) {
            let targetCameraX = me.x - canvas.width / 2;
            let targetCameraY = me.y - canvas.height / 2;
            // 限制镜头不超出地图边界
            cameraX = Math.max(0, Math.min(targetCameraX, mapWidth - canvas.width));
            cameraY = Math.max(0, Math.min(targetCameraY, mapHeight - canvas.height));
        }
        ctx.translate(-cameraX, -cameraY); // 移动画布，实现镜头效果
    } else { // 上帝视角
        const scale = Math.min(canvas.width / mapWidth, canvas.height / mapHeight);
        const offsetX = (canvas.width - mapWidth * scale) / 2;
        const offsetY = (canvas.height - mapHeight * scale) / 2;
        ctx.translate(offsetX, offsetY);
        ctx.scale(scale, scale); // 缩放画布以适应屏幕
    }

    // --- 绘制世界对象 ---
    // 绘制地图背景、炸弹点、障碍物等
    // 这些数据全部来自 latestGameState
    ctx.fillStyle = '#2d3748'; ctx.fillRect(0, 0, mapWidth, mapHeight);

    if (latestGameState.mode === 'DEMOLITION') {
        ctx.save();
        ctx.lineWidth = 4; ctx.font = "bold 48px Orbitron"; ctx.textAlign = "center"; ctx.textBaseline = "middle";
        [latestGameState.bombSiteA, latestGameState.bombSiteB].forEach((site, index) => {
            if (site) {
                ctx.strokeStyle = "rgba(255, 165, 0, 0.5)"; ctx.fillStyle = "rgba(255, 165, 0, 0.1)";
                ctx.fillRect(site.x, site.y, site.w, site.h); ctx.strokeRect(site.x, site.y, site.w, site.h);
                ctx.fillStyle = "rgba(255, 165, 0, 0.5)"; ctx.fillText(index === 0 ? "A" : "B", site.x + site.w / 2, site.y + site.h / 2);
            }
        });
        ctx.restore();
    }

    ctx.fillStyle = '#4a5568';
    latestGameState.obstacles.forEach(obs => {
        if (obs.type === 'RECTANGLE') ctx.fillRect(obs.x, obs.y, obs.w, obs.h);
        else if (obs.type === 'ELLIPSE') { ctx.beginPath(); ctx.ellipse(obs.x + obs.w/2, obs.y + obs.h/2, obs.w/2, obs.h/2, 0, 0, 2 * Math.PI); ctx.fill(); }
        else if (obs.type === 'POLYGON' && obs.xPoints) {
            ctx.beginPath(); ctx.moveTo(obs.xPoints[0], obs.yPoints[0]);
            for (let i = 1; i < obs.xPoints.length; i++) ctx.lineTo(obs.xPoints[i], obs.yPoints[i]);
            ctx.closePath(); ctx.fill();
        }
    });

    if (me && !isDead) calculateFOVIfNeeded(); else fovPoints = []; // 如果活着，计算视野

    // 绘制掉落的物品
    if (latestGameState.droppedItems) latestGameState.droppedItems.forEach(drawDroppedItem);
    // 绘制已安放的C4
    if (latestGameState.bombPlanted) drawPlantedBomb(latestGameState.bombTimer);

    // **遍历并绘制所有玩家和僵尸**
    [...latestGameState.players, ...latestGameState.zombies].forEach(p => {
        // 检查角色是否在视野内，如果在才绘制
        const characterCircle = { x: p.x, y: p.y, radius: PLAYER_SIZE / 2 };
        if (isSpectator || (isDead && latestGameState.mode !== 'TEAM_DEATHMATCH') || p.id === myPlayerId || isCircleVisibleInPolygon(characterCircle, fovPoints)) {
            drawPlayer(p);
        }
    });

    // 绘制视觉效果 (枪口火焰、弹道)
    latestGameState.vfx.forEach(drawVFX);

    ctx.restore(); // 恢复绘图状态

    // --- 绘制视口对象 (HUD, 战争迷雾等) ---
    drawFog(me, isDead, isSpectator); // 绘制战争迷雾
    if(me && me.isAlive) drawOffscreenIndicators(me); // 绘制屏幕外敌人/目标指示器
    updateHUD(); // 更新HUD信息
    if (showScoreboard) updateScoreboard(); // 如果按下Tab，更新计分板
    if (showBuyMenu) updateBuyMenu(); // 如果按下B，更新购买菜单
    if (latestGameState.isGameOver && gameRunning) showGameOver(); // 游戏结束
}

function drawPlantedBomb(bombTimer) {
    const bombPos = latestGameState.bombPosition;
    if (!bombPos) return;

    ctx.save();
    const size = 15;
    ctx.translate(bombPos.x, bombPos.y);

    const blinkSpeed = bombTimer < 10000 ? (bombTimer < 5000 ? 150 : 300) : 600;
    const alpha = (Date.now() % blinkSpeed) < (blinkSpeed / 2) ? 1.0 : 0.5;

    ctx.fillStyle = `rgba(255, 0, 0, ${alpha})`;
    ctx.strokeStyle = `rgba(255, 255, 255, ${alpha})`;
    ctx.lineWidth = 2;

    ctx.fillRect(-size / 2, -size / 2, size, size);
    ctx.strokeRect(-size / 2, -size / 2, size, size);

    ctx.fillStyle = "white";
    ctx.font = "bold 14px Orbitron";
    ctx.textAlign = "center";
    ctx.fillText("C4", 0, size);
    ctx.restore();
}

function drawDroppedItem(item) {
    const boxSize = 20;
    ctx.save();
    ctx.strokeStyle = item.isBomb ? 'red' : 'yellow';
    ctx.lineWidth = 2;
    ctx.strokeRect(item.x - boxSize / 2, item.y - boxSize / 2, boxSize, boxSize);

    ctx.fillStyle = 'white';
    ctx.font = "12px Orbitron";
    ctx.textAlign = "center";
    ctx.fillText(item.isBomb ? "C4" : (WEAPON_DATA[item.name]?.name || item.name), item.x, item.y + boxSize);
    ctx.restore();
}

// 绘制单个玩家
function drawPlayer(p) {
    if (p.health <= 0) return;
    ctx.save();
    if (p.isSlowed) { ctx.shadowBlur = 15; ctx.shadowColor = "#f56565"; }
    if (p.isInvincible) ctx.globalAlpha = 0.5;
    // 绘制玩家身体 (圆圈)
    ctx.fillStyle = p.team === 'CT' ? '#63B3ED' : (p.team === 'T' ? '#F56565' : '#48BB78');
    if (p.id === myPlayerId) ctx.fillStyle = '#68D391'; // 本地玩家用绿色高亮
    ctx.beginPath(); ctx.arc(p.x, p.y, PLAYER_SIZE / 2, 0, Math.PI * 2); ctx.fill();

    // 绘制表示朝向的“头”
    const headRadius = PLAYER_SIZE / 8, headDist = PLAYER_SIZE / 4;
    ctx.fillStyle = p.isInvincible ? 'rgba(255, 215, 0, 0.5)' : 'gold';
    ctx.beginPath(); ctx.arc(p.x + Math.cos(p.angle) * headDist, p.y + Math.sin(p.angle) * headDist, headRadius, 0, Math.PI * 2); ctx.fill();

    // 绘制枪管
    if (p.team !== 'ZOMBIE' && p.weaponName) {
        ctx.strokeStyle = '#CBD5E0'; ctx.lineWidth = 3;
        ctx.beginPath(); ctx.moveTo(p.x, p.y); ctx.lineTo(p.x + Math.cos(p.angle) * (PLAYER_SIZE / 2 + 5), p.y + Math.sin(p.angle) * (PLAYER_SIZE / 2 + 5)); ctx.stroke();
    }
    if (p.hasBomb) {
        ctx.fillStyle = 'red'; ctx.font = "bold 16px Orbitron";
        ctx.fillText("C4", p.x + PLAYER_SIZE / 2, p.y - PLAYER_SIZE / 2);
    }
    ctx.shadowBlur = 0;
    if (p.id !== myPlayerId) drawHealthBar(p);
    ctx.restore();
}

function drawHealthBar(p) {
    const barWidth = 30, barHeight = 5, x = p.x - barWidth / 2, y = p.y - PLAYER_SIZE / 2 - barHeight - 5;
    ctx.fillStyle = '#E53E3E'; ctx.fillRect(x, y, barWidth, barHeight);
    ctx.fillStyle = '#48BB78'; ctx.fillRect(x, y, barWidth * (p.health / 100.0), barHeight);
}
function drawVFX(vfx) {
    if (vfx.type === 'muzzle') { ctx.fillStyle = 'orange'; ctx.beginPath(); ctx.arc(vfx.x, vfx.y, 5, 0, Math.PI * 2); ctx.fill(); }
    else if (vfx.type === 'trail') {
        ctx.strokeStyle = 'rgba(255, 255, 150, 0.8)'; ctx.lineWidth = 2;
        ctx.beginPath(); ctx.moveTo(vfx.x, vfx.y); ctx.lineTo(vfx.endX, vfx.endY); ctx.stroke();
    }
}

function drawFog(me, isDead, isSpectator) {
    const fogColor = cameraMode === 'follow' ? 'rgba(26, 32, 44, 0.85)' : 'rgba(26, 32, 44, 0.5)';
    if (isSpectator || (isDead && latestGameState.mode !== 'TEAM_DEATHMATCH')) return;
    if (isDead && latestGameState.mode === 'TEAM_DEATHMATCH') {
        ctx.fillStyle = 'rgba(26, 32, 44, 0.9)'; ctx.fillRect(0, 0, canvas.width, canvas.height); return;
    }
    if (me && fovPoints.length > 0) {
        ctx.save();

        ctx.fillStyle = fogColor;
        ctx.beginPath();

        // Use canvas coordinates for fog overlay
        ctx.moveTo(0, 0); ctx.lineTo(canvas.width, 0); ctx.lineTo(canvas.width, canvas.height); ctx.lineTo(0, canvas.height); ctx.closePath();

        // Transform FOV points from world to canvas coordinates
        const fovCanvasPoints = fovPoints.map(p => {
            if (cameraMode === 'follow') {
                return { x: p.x - cameraX, y: p.y - cameraY };
            } else {
                const scale = Math.min(canvas.width / mapWidth, canvas.height / mapHeight);
                const offsetX = (canvas.width - mapWidth * scale) / 2;
                const offsetY = (canvas.height - mapHeight * scale) / 2;
                return { x: p.x * scale + offsetX, y: p.y * scale + offsetY };
            }
        });

        ctx.moveTo(fovCanvasPoints[0].x, fovCanvasPoints[0].y);
        for (let i = 1; i < fovCanvasPoints.length; i++) ctx.lineTo(fovCanvasPoints[i].x, fovCanvasPoints[i].y);
        ctx.closePath();

        ctx.fill('evenodd');
        ctx.restore();
    }
}

function drawOffscreenIndicators(me) {
    if (cameraMode !== 'follow') return;

    const allEntities = [...latestGameState.players, ...(latestGameState.soundEvents || []), ...(latestGameState.droppedItems || [])];
    const playerScreenX = me.x - cameraX;
    const playerScreenY = me.y - cameraY;


    allEntities.forEach(entity => {
        let pos, type, color, shouldDraw = true;

        if (entity.id && entity.isAlive && entity.id !== me.id) { // It's a player
            pos = entity;
            if (entity.team !== me.team) {
                type = 'player_enemy';
                color = 'rgba(245, 101, 101, 0.8)'; // red
                // NEW LOGIC: Only show enemy indicator if they are in the FOV
                const enemyCircle = { x: pos.x, y: pos.y, radius: PLAYER_SIZE / 2 };
                if (!isCircleVisibleInPolygon(enemyCircle, fovPoints)) {
                    shouldDraw = false;
                }
            } else {
                type = 'player_team';
                color = 'rgba(99, 179, 237, 0.8)'; // blue
            }
        } else if (entity.type === 'FIRE') { // It's a sound event
            pos = entity;
            type = 'sound';
            color = 'rgba(203, 213, 224, 0.7)'; // gray
        } else if (entity.isBomb !== undefined) { // Check for dropped item property
            pos = {x: entity.x, y: entity.y}; // Dropped items have x/y directly
            if (entity.isBomb) {
                type = 'bomb';
                color = 'rgba(255, 255, 0, 0.8)'; // yellow
            } else {
                shouldDraw = false; // Don't show indicators for regular dropped guns
            }
        } else {
            shouldDraw = false;
        }

        if (!shouldDraw) return;

        const screenX = pos.x - cameraX;
        const screenY = pos.y - cameraY;

        // If entity is on screen, don't draw an indicator
        if (screenX > PLAYER_SIZE && screenX < canvas.width - PLAYER_SIZE && screenY > PLAYER_SIZE && screenY < canvas.height - PLAYER_SIZE) {
            return;
        }

        const angle = Math.atan2(screenY - playerScreenY, screenX - playerScreenX);

        const padding = 30; // Increased padding
        const bounds = {
            left: padding,
            top: padding,
            right: canvas.width - padding,
            bottom: canvas.height - padding
        };

        const cosAngle = Math.cos(angle);
        const sinAngle = Math.sin(angle);

        let t = Infinity;

        if (cosAngle !== 0) {
            let t_v = cosAngle > 0 ? (bounds.right - playerScreenX) / cosAngle : (bounds.left - playerScreenX) / cosAngle;
            if (t_v > 0) {
                const y_intersect = playerScreenY + t_v * sinAngle;
                if (y_intersect >= bounds.top && y_intersect <= bounds.bottom) {
                    t = Math.min(t, t_v);
                }
            }
        }

        if (sinAngle !== 0) {
            let t_h = sinAngle > 0 ? (bounds.bottom - playerScreenY) / sinAngle : (bounds.top - playerScreenY) / sinAngle;
            if (t_h > 0) {
                const x_intersect = playerScreenX + t_h * cosAngle;
                if (x_intersect >= bounds.left && x_intersect <= bounds.right) {
                    t = Math.min(t, t_h);
                }
            }
        }

        if (t === Infinity) return; // Should not happen if target is offscreen

        const indicatorX = playerScreenX + t * cosAngle;
        const indicatorY = playerScreenY + t * sinAngle;


        ctx.save();
        ctx.translate(indicatorX, indicatorY);
        ctx.rotate(angle);
        ctx.fillStyle = color;

        if (type.startsWith('player')) { // Draw triangle for players
            ctx.beginPath();
            ctx.moveTo(12, 0);
            ctx.lineTo(-6, -8);
            ctx.lineTo(-6, 8);
            ctx.closePath();
            ctx.fill();
        } else if (type === 'sound') { // Draw circle for sounds
            ctx.beginPath();
            ctx.arc(0, 0, 8, 0, 2 * Math.PI);
            ctx.fill();
        } else if (type === 'bomb') { // Draw square for bomb
            ctx.fillRect(-7, -7, 14, 14);
        }
        ctx.restore();
    });
}


// **核心: 更新HUD**
function updateHUD() {
    const state = latestGameState; if (!state) return;

    // 根据游戏模式显示/隐藏不同的UI元素
    const isTDM = state.mode === 'TEAM_DEATHMATCH';
    const isDemo = state.mode === 'DEMOLITION';
    const isZombie = state.mode === 'ZOMBIE_MODE';

    document.getElementById('team-scores').style.display = (isTDM || isDemo) ? 'block' : 'none';
    document.getElementById('zombie-info').style.display = isZombie ? 'block' : 'none';
    document.getElementById('change-weapon-prompt').style.display = isTDM ? 'block' : 'none';
    document.getElementById('player-money').style.display = isDemo ? 'block' : 'none';
    document.getElementById('buy-prompt').style.display = isDemo && state.roundPhase === 'FREEZE_TIME' ? 'block' : 'none';
    document.getElementById('loss-bonus-container').style.display = isDemo ? 'flex' : 'none';

    const roundCounterEl = document.getElementById('round-counter');
    if (roundCounterEl) {
        roundCounterEl.style.display = isDemo ? 'block' : 'none';
        if(isDemo) roundCounterEl.textContent = `回合: ${state.round} / ${DEMOLITION_MAX_ROUNDS}`;
    }

    const roundEndEl = document.getElementById('round-end-announcement');
    if(isDemo && state.roundPhase === 'ROUND_OVER') {
        document.getElementById('round-winner-text').textContent = `${state.roundWinner}方胜利!`;
        document.getElementById('round-winner-text').style.color = state.roundWinner === 'CT' ? '#63b3ed' : '#f56565';
        document.getElementById('round-win-reason-text').textContent = state.roundWinReason;
        roundEndEl.classList.remove('hidden');
    } else { roundEndEl.classList.add('hidden'); }

    // 从 `state` (即 `latestGameState`) 中读取数据并更新到HTML元素中
    if (isTDM) {
        document.getElementById('ct-score').textContent = `CT: ${state.ctScore}`; document.getElementById('t-score').textContent = `T: ${state.tScore}`;
        document.getElementById('game-timer').textContent = `时间: ${Math.floor(state.time / 60)}:${(state.time % 60).toString().padStart(2, '0')}`;
    } else if (isDemo) {
        document.getElementById('ct-score').innerHTML = `CT: ${state.ctScore}`;
        document.getElementById('t-score').innerHTML = `T: ${state.tScore}`;
        const timer = state.bombPlanted ? state.bombTimer : state.roundTime;
        const timeColor = state.bombPlanted ? '#F56565' : (state.roundPhase === 'FREEZE_TIME' ? '#F6E05E' : 'white');
        document.getElementById('game-timer').innerHTML = `${state.roundPhase === 'FREEZE_TIME' ? '准备: ' : ''}${Math.floor(timer / 1000).toString().padStart(2, '0')}`;
        document.getElementById('game-timer').style.color = timeColor;
    } else if (isZombie) {
        document.getElementById('wave-counter').textContent = `波数: ${state.wave}`;
        document.getElementById('zombie-counter').textContent = `僵尸: ${state.zombiesLeft}`;
        const waveAnnEl = document.getElementById('wave-announcement');
        waveAnnEl.textContent = state.nextWaveIn ? `下一波: ${state.nextWaveIn}`: '';
        waveAnnEl.classList.toggle('hidden', !state.nextWaveIn);
    }

    document.getElementById('tps-counter').textContent = `TPS: ${tps}`;
    const me = state.players.find(p => p.id === myPlayerId);
    if (me) { // 如果找到了本地玩家的数据
        // 更新血量、护甲、金钱、弹药等
        document.getElementById('player-hp').textContent = `HP: ${me.health > 0 ? me.health : '死亡'}`;
        document.getElementById('player-hp').style.color = me.health > 50 ? '#48BB78' : (me.health > 20 ? '#F6E05E' : '#F56565');

        const armorEl = document.getElementById('player-armor');
        if (me.armorValue > 0) {
            armorEl.textContent = `护甲: ${me.armorValue}`;
            armorEl.style.display = 'block';
        } else {
            armorEl.style.display = 'none';
        }

        const lossBonusContainer = document.getElementById('loss-bonus-container');
        lossBonusContainer.innerHTML = '连败补偿: ';
        for(let i = 0; i < 4; i++) {
            const bar = document.createElement('div');
            bar.className = `w-4 h-4 rounded-sm ml-1 ${i < me.consecutiveLosses ? 'bg-red-500' : 'bg-gray-600'}`;
            lossBonusContainer.appendChild(bar);
        }

        document.getElementById('player-weapon').textContent = `武器: ${me.weaponName || '选择中...'}`;
        document.getElementById('player-money').textContent = `$${me.money}`;
        const ammoEl = document.getElementById('player-ammo');
        if (me.isReloading) { ammoEl.textContent = `换弹中...`; ammoEl.style.color = '#F6E05E'; }
        else { ammoEl.textContent = `弹药: ${me.currentAmmo} / ${me.reserveAmmo === 999 ? '∞' : me.reserveAmmo}`; ammoEl.style.color = 'white'; }

        const equipContainer = document.getElementById('player-equipment');
        equipContainer.innerHTML = '';
        if (me.hasKevlar) {
            const armorIcon = document.createElement('img');
            armorIcon.src = me.hasHelmet ? './icons/helmet.svg' : './icons/kevlar.svg';
            armorIcon.className = 'w-8 h-8';
            equipContainer.appendChild(armorIcon);
        }
        if (me.equipment) me.equipment.forEach(equip => {
            const icon = document.createElement('div');
            icon.className = 'w-8 h-8 bg-gray-600 rounded flex items-center justify-center text-xs';
            icon.textContent = equip.name.substring(0,2) + (equip.count > 1 ? `x${equip.count}` : '');
            equipContainer.appendChild(icon);
        });
        if (me.hasDefuseKit) {
            const icon = document.createElement('div');
            icon.className = 'w-8 h-8 bg-blue-600 rounded flex items-center justify-center text-xs font-bold'; icon.textContent = 'Kit'; equipContainer.appendChild(icon);
        }

        // 更新交互进度条
        const barContainer = document.getElementById('interaction-bar-container');
        const barProgress = document.getElementById('interaction-bar-progress');
        if (me.isInteracting) { // 如果正在交互
            barContainer.style.display = 'block';
            let progress = 0;
            // 根据服务器发来的交互开始时间计算进度
            const interactionDuration = Date.now() - me.interactionStart;
            if(state.bombPlanted && me.team === 'CT') { // Defusing
                const requiredTime = me.hasDefuseKit ? DEMO_DEFUSE_WITH_KIT_TIME_MS : DEMO_DEFUSE_TIME_MS;
                progress = (interactionDuration / requiredTime) * 100;
            } else if (me.hasBomb && me.team === 'T') { // Planting
                progress = (interactionDuration / DEMO_PLANT_TIME_MS) * 100;
            }
            barProgress.style.width = `${Math.min(100, progress)}%`;
        } else {
            barContainer.style.display = 'none';
        }

    } else { /* 观战者HUD */ }
}

function updateScoreboard() {
    const container = document.getElementById('scoreboard-teams'); container.innerHTML = '';
    if (!latestGameState) return;
    const players = latestGameState.players.sort((a, b) => b.score - a.score);
    if (latestGameState.mode === 'TEAM_DEATHMATCH' || latestGameState.mode === 'DEMOLITION') {
        const ctPlayers = players.filter(p => p.team === 'CT'), tPlayers = players.filter(p => p.team === 'T');
        container.appendChild(createTeamTable(ctPlayers, '反恐精英', 'team-ct'));
        container.appendChild(createTeamTable(tPlayers, '恐怖分子', 'team-t'));
    } else { container.appendChild(createTeamTable(players, '幸存者', 'team-ct')); }
}
function createTeamTable(players, teamName, teamClass) {
    const table = document.createElement('table'); table.className = 'w-full';
    let html = `<thead class="${teamClass}"><tr>
                    <th class="col-name">${teamName}</th>
                    <th class="col-weapon">武器</th>
                    <th class="col-k">K</th> <th class="col-d">D</th> <th class="col-dmg">DMG</th>
                    <th class="col-acc">ACC</th> <th class="col-hs">HS%</th> <th class="col-ping">Ping</th>
                </tr></thead><tbody>`;
    players.forEach(p => {
        const isLocal = p.id === myPlayerId;
        const name = p.name || (p.isAI ? 'BOT' : (isLocal ? '你' : 'Player'));
        const pingVal = isLocal ? ping : 'N/A';
        const rowClass = isLocal ? 'local-player-row' : '';
        const weapon = p.weaponName || '手枪';
        const accuracy = p.totalShotsFired > 0 ? ((p.totalShotsHit / p.totalShotsFired) * 100).toFixed(1) + '%' : '0.0%';
        const headshotRate = p.totalShotsHit > 0 ? ((p.totalHeadshots / p.totalShotsHit) * 100).toFixed(1) + '%' : '0.0%';
        html += `<tr class="${rowClass}">
                    <td class="col-name">${name}</td> <td class="col-weapon">${weapon}</td>
                    <td class="col-k">${p.score||0}</td> <td class="col-d">${p.deaths||0}</td> <td class="col-dmg">${p.damageDealt||0}</td>
                    <td class="col-acc">${accuracy}</td> <td class="col-hs">${headshotRate}</td> <td class="col-ping">${pingVal}</td>
                   </tr>`;
    });
    table.innerHTML = html + '</tbody>'; return table;
}

function showGameOver() {
    stopGameLoop();
    const screen = document.getElementById('game-over'), title = document.getElementById('game-over-title');
    if (latestGameState.mode === 'TEAM_DEATHMATCH' || latestGameState.mode === 'DEMOLITION') {
        const score = latestGameState.mode === 'DEMOLITION' ? [latestGameState.ctScore, latestGameState.tScore] : [latestGameState.ctScore, latestGameState.tScore];
        title.textContent = score[0] > score[1] ? "反恐精英胜利" : (score[1] > score[0] ? "恐怖分子胜利" : "平局");
    } else { title.textContent = `游戏结束 - 存活 ${latestGameState.wave - 1} 波`; }
    screen.style.display = 'flex';
}

// **核心: 设置输入监听器**
function setupInputListeners() {
    window.addEventListener('keydown', e => {
        const key = e.key.toUpperCase();
        if ('WASD'.includes(key)) keysDown.add(key); // 将按下的移动键添加到Set中
        if (key === 'R') socket.send(JSON.stringify({ type: 'requestReload' })); // 发送换弹请求
        if (key === 'G' && latestGameState.mode === 'DEMOLITION') {
            socket.send(JSON.stringify({ type: 'dropWeapon' }));
        }
        if (key === 'B' && latestGameState && latestGameState.mode === 'TEAM_DEATHMATCH') {
            const selector = document.getElementById('tdm-weapon-selector');
            selector.style.display = (selector.style.display === 'none' || selector.style.display === '') ? 'flex' : 'none';
            if (selector.style.display === 'flex') openInGameWeaponMenu();
        }
        if (key === 'B' && latestGameState && latestGameState.mode === 'DEMOLITION') {
            if(latestGameState.roundPhase === 'FREEZE_TIME' || showBuyMenu) { // Allow closing menu anytime
                showBuyMenu = !showBuyMenu;
                buyMenu.style.display = showBuyMenu ? 'flex' : 'none';
            }
        }
        if (key === 'E' && latestGameState.mode === 'DEMOLITION') {
            if (!isInteracting) {
                socket.send(JSON.stringify({ type: 'startInteraction' }));
                isInteracting = true;
            }
        }
        if (e.key === 'Tab') { e.preventDefault(); showScoreboard = true; scoreboard.style.display = 'flex'; }
    });
    window.addEventListener('keyup', e => {
        const key = e.key.toUpperCase();
        if ('WASD'.includes(key)) keysDown.delete(key); // 抬起时从Set中移除
        if (key === 'E' && latestGameState.mode === 'DEMOLITION') {
            if (isInteracting) {
                socket.send(JSON.stringify({ type: 'stopInteraction' }));
                isInteracting = false;
            }
        }
        if (e.key === 'Tab') { showScoreboard = false; scoreboard.style.display = 'none'; }
    });
    canvas.addEventListener('mousemove', e => { const rect = canvas.getBoundingClientRect(); mousePos.x = e.clientX - rect.left; mousePos.y = e.clientY - rect.top; });
    canvas.addEventListener('mousedown', () => { isShooting = true; }); // 按下鼠标，设置射击状态为true
    canvas.addEventListener('mouseup', () => { isShooting = false; }); // 松开鼠标，设置射击状态为false
}

function setupDynamicUI() {
    const hud = document.getElementById('hud');
    if (!hud) return;

    const topRightContainer = hud.children[0].children[2]; // Assumes the structure is consistent

    let cameraButton = document.getElementById('camera-toggle-btn');
    if (!cameraButton) {
        cameraButton = document.createElement('button');
        cameraButton.id = 'camera-toggle-btn';
        cameraButton.textContent = '[切换视角]';
        cameraButton.className = 'text-sm text-yellow-300 hud-element mt-1 pointer-events-auto cursor-pointer';
        cameraButton.onclick = () => {
            cameraMode = (cameraMode === 'follow') ? 'full' : 'follow';
        };
        topRightContainer.appendChild(cameraButton);
    }
}
// 页面加载完成后执行初始化
document.addEventListener('DOMContentLoaded', () => {
    preloadSounds();
    createBuyMenu();
    setupDynamicUI(); // Create the camera button
    connect(); // 开始连接服务器
});
