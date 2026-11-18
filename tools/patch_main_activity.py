#!/usr/bin/env python3
# Applied to a copy of the user's 2026-11-17 MainActivity.kt, not to the user's local machine.
from pathlib import Path
p=Path(__file__).resolve().parents[1]/'app/src/main/java/com/example/qstylemusic/MainActivity.kt'
s=p.read_text(encoding='utf-8')
def once(old, new):
    global s
    if s.count(old)!=1: raise RuntimeError(f'Expected exactly one occurrence: {old!r}. Found {s.count(old)}')
    s=s.replace(old,new)
once('import androidx.compose.material.icons.rounded.Refresh\n',
     'import androidx.compose.material.icons.rounded.Refresh\nimport androidx.compose.material.icons.rounded.Extension\n')
once('import com.example.qstylemusic.ui.screens.MineScreen\n',
     'import com.example.qstylemusic.ui.screens.MineScreen\nimport com.example.qstylemusic.ui.screens.MplugManagerScreen\n')
once('    var playerExpanded by rememberSaveable { mutableStateOf(false) }\n',
     '    var playerExpanded by rememberSaveable { mutableStateOf(false) }\n    var showPlugins by rememberSaveable { mutableStateOf(false) }\n    BackHandler(enabled = showPlugins && !playerExpanded) { showPlugins = false }\n')
once('                    playback = playback,\n                    isFavorite',
     '                    playback = playback,\n                    playQueue = playQueue,\n                    isFavorite')
once('                    onCyclePlayMode = vm::cyclePlayMode\n                )\n            } else {',
     '                    onCyclePlayMode = vm::cyclePlayMode,\n                    onChooseQueueItem = vm::playQueueItem\n                )\n            } else if (showPlugins) {\n                MplugManagerScreen(onBack = { showPlugins = false })\n            } else {')
once('                                IconButton(onClick = { if (granted) vm.refreshSongs() else permissionLauncher.launch(permission) }) {\n                                    Icon(Icons.Rounded.Refresh, "重新扫描本地音乐")\n                                }',
     '                                Row {\n                                    if (selected == MusicTab.MINE) {\n                                        IconButton(onClick = { showPlugins = true }) {\n                                            Icon(Icons.Rounded.Extension, "音源插件管理")\n                                        }\n                                    }\n                                    IconButton(onClick = { if (granted) vm.refreshSongs() else permissionLauncher.launch(permission) }) {\n                                        Icon(Icons.Rounded.Refresh, "重新扫描本地音乐")\n                                    }\n                                }')
once('                                        { vm.playSong(it) }, vm::toggleFavorite\n                                    )',
     '                                        { song ->\n                                            vm.playSong(song)\n                                            playerExpanded = true\n                                        }, vm::toggleFavorite\n                                    )')
p.write_text(s,encoding='utf-8')
print('MainActivity patched:',p)
