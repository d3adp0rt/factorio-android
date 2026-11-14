# Factorio for Android — состояние проекта

Последнее обновление: 14.11.2026 — **APK 0.4.0**: `FactorioAndroid-0.4.0-arm64.apk` (141 МБ,
с Linux-окружением) и `FactorioAndroid-0.4.0-dev.apk` (18 МБ, без окружения).

> **0.4.0 — игра больше не входит в APK.**
> * APK содержит только окружение (Arch Linux ARM + Mesa + X-библиотеки, 123 МБ zstd).
>   При первом запуске лаунчер просит tar-архив своей сборки Factorio (`.tar`, `.tar.gz`,
>   `.tar.xz`, `.tar.zst` — определяется по magic-байтам, `GameInstaller.java`), распаковывает
>   его в `/opt/factorio` через staging-каталог (сломанный архив не убивает рабочую установку)
>   и предлагает удалить архив (SAF-провайдеры часто не дают — тогда показываем имя файла).
> * Плашка в лаунчере: версия из `data/base/info.json` и наличие Space Age
>   (`data/space-age` или `mod.json` с `name: space-age`).
> * Импорт сохранений и модов из хранилища телефона (копирование в `saves/` и `mods/`).
> * Настройки разбиты на секции (Игра / Графика / Управление / Прочее), у каждого пункта
>   кнопка ⓘ с пояснением.
> * Язык интерфейса: системный / русский / английский (`I18n.wrap` в `attachBaseContext`).
> * Редактор раскладки: перетаскивание, добавление/удаление, смена действия, сохранение
>   (`ControlsEditorActivity` + `TouchControlsView` в edit-режиме).
> * Пресет геймпада: аналоговый стик (4 направления → WASD) + кнопки как на контроллере;
>   настоящий геймпад, подключённый к телефону, транслируется в клавиши/мышь
>   (`GamepadMapper`). Настоящее evdev-устройство создать нельзя: `/dev/uinput` закрыт
>   SELinux для `untrusted_app`, поэтому режим «геймпад» в самой Factorio недоступен.
> * Окно игры ставится на весь экран через `xdotool` (в контейнере нет WM, окно оставалось
>   в углу). `xdotool` и `libpulse` теперь в базовом образе.
> * Overlay-механизм удалён: `run.sh` живёт в репозитории (`rootfs/opt/factorio/run.sh`) и
>   кладётся в образ при сборке, лишние библиотеки — в списке пакетов. APK похудел на 15 МБ.
> * `SETUP_VERSION` = 16. Для центрирования окна нужен свежий образ (wipe в настройках),
>   иначе в старой распаковке нет `xdotool`.
>
> **Репозиторий:** https://github.com/d3adp0rt/factorio-android (public, GPLv3, ветка `main`).
> Игра и APK в `.gitignore`; в репозитории только исходники (5.1k файлов). Подмодули
> termux-x11 (`xserver`, `libx11`, `pixman`, …) **вендорены как обычные файлы** — у них были
> локальные патчи, без которых дерево не собирается, а `.gitmodules` указывал на старые пути.
> CI `.github/workflows/build.yml`: job `rootfs` (Arch-контейнер, `pacman -Syu`, кеш по хешу
> скриптов) → job `apk` (NDK из `lorie/version.gradle`, meson-сборка virglrenderer, оба APK)
> → на тег `v*` публикация релиза с описанием. Локальная сборка 0.4.0 проверена.

Последнее обновление: 14.11.2026 — **APK**: `FactorioAndroid-0.3.1-arm64.apk` (1.58 ГБ) и
**`FactorioAndroid-0.3.1-dev.apk` (33 МБ)** — dev-сборка без rootfs в ассетах: она берёт архив
из `/sdcard/Download/rootfs.tar.zst` (или `Android/data/<pkg>/files/`), а при установке поверх
полной версии просто переиспользует уже распакованный rootfs. Дальше итерируем только dev-APK.

> **0.3.1 — «нет коннекта к X серверу» на загрузке спрайтов: виноват мой же тюнинг графики.**
> В логе видно `XIO: fatal IO error 13 (Permission denied) on X server ":0"` через 20–25 с после
> `Parallel sprite loader initialized`. Разбор по двум прогонам:
> * 0.2.4 (17:25, `config.ini` ещё не существовал) — `[DXT: low-quality] [Tex.Stream.: true]`,
>   `Estimated VRAM usage for textures: 507 MB`, игра дошла до меню и прожила 111 с.
> * 0.3.0 (17:38 и 17:39, `config.ini` уже есть) — `[DXT: none] [Tex.Stream.: false]`, атласы
>   все `[not-compressed]`, до `Sprites loaded` не доходит — соединение с X рвётся.
>
> Причина: `run.sh` применял правки, придуманные для llvmpipe, **и на GPU-пути**:
> `texture-compression-level=none`, `texture-streaming=false`, `compress-virtual-atlas=false`.
> На GPU это ровно наоборот вредно: без DXT атласы занимают вчетверо больше, без стриминга всё
> резидентное, без сжатия виртуального атласа — ещё больше. Приложение упирается в бюджет
> памяти, и LMK прибивает процесс X-сервера (SIGKILL — shutdown-hook не успевает записать
> «X server process is exiting», поэтому в логе следа нет). Игра остаётся без X — отсюда
> `XIO` и «нет коннекта» в окне.
>
> Исправлено: строки теперь всегда удаляются, а вставляются **только при `BACKEND=cpu`**.
> На virgl остаются штатные настройки игры (сжатие DXT + стриминг + сжатый виртуальный атлас) —
> то есть ровно конфигурация, на которой 0.2.4 отработала 111 с.
> Плюс `Game.hint()` теперь пишет в лог человеческую строку: `>> lost the X server. If this
> happened while loading sprites the device ran out of memory...`, и аналогично про смерть
> render-сервера — чтобы такой случай больше не расшифровывать по сырому XIO.
> `OVERLAY_VERSION` = 15 (run.sh), `SETUP_VERSION` = 15.

> **0.3.0 — GPU работает (virgl). Zink и HolyGL4ES вырезаны. Заложено экранное управление.**
>
> **Что вырезано:** `zink` (Venus) и `gl4es` удалены из меню, из `Game.java`, из `Config`
> (`zink`/`gl4es` в старых префах молча становятся `virgl`), из overlay (`/opt/gl4es`,
> `/usr/share/vulkan`, `libvulkan*.so*`), из `Setup` (копирование `/system/lib64/libvulkan.so`
> под именем `libvulkan.so.1`), `tools/build-gl4es.sh` удалён. Причины в записях 0.2.2/0.2.3:
> zink упёрся в `VK_EXT_robustness2::nullDescriptor` (Mesa 26 требует, Venus/Mali не даёт),
> GL4ES не компилирует шейдеры Factorio с `#version 330`. Сервер по-прежнему собран с
> `venus=true` (не пересобирали рабочий бинарник), но флаг `--venus` больше не передаётся.
> В меню осталось два пункта: **CPU (llvmpipe)** и **VirGL**.
>
> **Экранное управление — фундамент.** Три слоя, каждый можно менять отдельно:
> 1. `app/src/main/res/raw/controls_default.json` — сама раскладка: список кнопок с позицией
>    в долях экрана (`x,y,w,h`), подписью и действием. Действия: `key` (нажать, или
>    `"hold":true` — держать), `mouse` (`left/right/middle`, с `hold`), `wheel` (`delta`),
>    `toggle` (свернуть панель, остаётся кнопка `≡`).
> 2. `lorie/.../controls/ControlsSpec.java` + `ButtonSpec.java` — парсер этого JSON и карта
>    имён клавиш (`W`, `TAB`, `SHIFT`, `KEYCODE_F5`, …). Раскладка живёт в отдельном
>    SharedPreferences-файле `factorio_controls`, поэтому её можно править чем угодно.
> 3. `lorie/.../controls/TouchControlsView.java` — прозрачный оверлей над X-экраном:
>    рисует кнопки, инжектит события через `LorieView` (он реализует `InputStub`), а пальцы,
>    которые начались **вне** кнопок, переотдаёт тачпаду как отдельный жест.
>    Android привязывает весь жест к одному ребёнку, поэтому оверлей всегда забирает жест себе
>    и ре-диспатчит «чужие» указатели через `MotionEvent.obtain` (`MotionEvent.split()` —
>    hidden API), отслеживая состояние жеста тачпада, чтобы тот видел корректные
>    DOWN/POINTER_DOWN/UP/MOVE.
>
> Итог: можно идти крестовиной (WASD) и одной рукой вести мышь/строить второй — это главное,
> чего не давало простое «оверлей поверх с перехватом».
> Дефолтная раскладка: WASD слева внизу, LMB (с удержанием)/RMB/колесо справа внизу,
> SHIFT/CTRL/Q/R слева сверху, E/M/TAB/ESC справа сверху, `≡` по центру сверху.
> В настройках приложения — галочка «Экранное управление» и кнопка сброса раскладки.
> `SETUP_VERSION` = 15, `OVERLAY_VERSION` = 14, `cfg_version` = 5.

> **0.2.4 — virgl доехал до загрузки спрайтов (17:22, 8.6 с). Добил таймер-квери.**
> Прогресс: `[Version] 4.3`, моды загрузились (checksum-ы посчитались), `Crop bitmaps`,
> `Parallel sprite loader initialized (threads: 8, bitmaps: 4741)` → падение на 32% загрузки.
> Убил не сам по себе отказ, а **исчерпанный бюджет tolerant-режима**: Factorio меряет
> GPU-время (`PIPE_QUERY_TIMESTAMP`, тип 2) на каждый кадр, каждый такой цикл даёт
> `CREATE_OBJECT`/`END_QUERY`/`GET_QUERY_RESULT` = 3 ошибки, 128 набралось за 8 секунд,
> после чего контекст снова честно умер.
> Правильное решение — не глушить, а **эмулировать**: у Mali GLES нет
> `EXT_disjoint_timer_query` (`FEAT(timer_query, 33, UNAVAIL, …)` — на GLES выключено в самом
> virglrenderer). Теперь `vrend_create_query()` для `PIPE_QUERY_TIMESTAMP` /
> `TIME_ELAPSED` / `TIMESTAMP_DISJOINT` без хостовой поддержки создаёт «фейковый» квестор
> (`q->fake`): begin/end — no-op, результат — `qstate: DONE, result: 0`. Ошибок не остаётся
> вообще, бюджет поднят до 4096 на всякий случай.
> Патчи проверены на чистом `git show HEAD:` — воспроизводят то, что в APK.
> `SETUP_VERSION` = 14.

> **0.2.3 — tolerant-режим сам себя топил. Итог по трём путям после 0.2.2.**
> В 0.2.2 `query create rejected: type 2` (это `PIPE_QUERY_TIMESTAMP` — Factorio меряет
> GPU-время), и дальше `END_QUERY`/`GET_QUERY_RESULT` тоже падали. Всё это проглатывалось,
> но мой же код вызывал `vrend_report_buffer_error()` — а он ставит `ctx->in_error = true`,
> после чего `vrend_draw_vbo()` навсегда отдаёт `ENOTRECOVERABLE` (**131** — ровно то, что
> видно в логе: `failed to dispatch DRAW_VBO: 131`), клиент отваливается.
> Исправлено: в tolerant-ветке `vrend_report_buffer_error()` больше не вызывается, а флаг
> сбрасывается новой `vrend_context_clear_error()` (она в `vrend_renderer.c`, т.к. `in_error`
> приватный; прототип в `vrend_renderer.h`). Патчи `tools/build-virgl.sh` проверены на чистом
> `git show HEAD:` — воспроизводят ровно то, что в APK.
>
> Состояние трёх GPU-путей:
> * **virgl** — доходит до `[Version] 4.3`, рисует, и теперь не должен отравляться таймер-квери.
>   Единственная известная потеря — GPU-тайминги Factorio.
> * **zink** — упёрся в жёсткий барьер guest-Mesa: `Zink requires the nullDescriptor feature of
>   KHR/EXT robustness2`. Mesa 26.2 требует `VK_EXT_robustness2::nullDescriptor` (Venus/Mali этого
>   не отдаёт), обхода нет — либо откатывать Mesa в госте, либо менять путь. `--venus --use-gles`
>   при этом работает: сервер поднимается, `proxy server with pid -1`, контексты создаются.
> * **gl4es** — версия прошла (`[Version] 3.3`), но GL4ES подавился первым же шейдером Factorio:
>   `Failed to compile shader '__core__/graphics/shaders/sprite.vert'`,
>   `0:9(1): error: syntax error`. Ожидаемо: у него нет GL 3.3. Опция оставлена как есть.

> **0.2.2 — первый прогон 0.2.1 дал три конкретные причины, все три починены.**
> 1. **virgl почти заработал**: `gl_version 32 - es profile enabled`, `GLSL feature level 430`,
>    гость видит `virgl (Mali-G925-Immortalis MC11), 4.3 Core`, Factorio даже нарисовал кадр,
>    а потом `context 1 failed to dispatch CREATE_OBJECT: 22`.
>    `0x40901` = длина 4, `obj_type = 9` = **`VIRGL_OBJECT_QUERY`**. Длина верная
>    (`VIRGL_OBJ_QUERY_SIZE == 4`), значит это молчаливый `err = EINVAL` из
>    `vrend_create_query()` — одна из проверок `has_feature(...)` (pipeline statistics /
>    timer / occlusion predicate / tf-overflow), т.е. Mali GLES не даёт нужного расширения
>    (в логе Factorio прямо: `ARB_pipeline_statistics_query:NO`). Такой отказ убивал **всю**
>    команду-буфер, а с ней и игру.
>    Патчи: (а) в `vrend_create_query()` добавлен `virgl_warn("query create rejected: type %d index %d")`
>    — теперь видно, какой именно тип отвергнут; (б) **tolerant mode** в
>    `vrend_decode_ctx_submit_cmd()`: команда с `EINVAL` пропускается (`continue`), а не рвёт
>    контекст; бюджет 128 ошибок на контекст, чтобы настоящий рассинхрон всё-таки останавливался.
> 2. **zink**: `failed to initialize vrend winsys` → `virgl_test_server --venus` тоже инициализирует
>    vrend/EGL до venus, а без `--use-gles` просит desktop-GL контекст. Теперь сервер
>    запускается как `--venus --use-gles`.
> 3. **gl4es**: дошёл до `glXChooseFBConfig`, поднялся и отдал `GL 3.3 gl4es wrapper 1.1.4`,
>    но Factorio сказал `[Version] 2.1`. Версию для гейта игра берёт из
>    `glGetIntegerv(GL_MAJOR_VERSION/GL_MINOR_VERSION)`, а это в GL4ES — `LIBGL_GL`, не
>    `LIBGL_VERSION` (тот только строка). Выставлено `LIBGL_GL=33`.
> `SETUP_VERSION` = 12, `OVERLAY_VERSION` = 13.

> **0.2.1 — два бага из первого прогона 0.2.0, оба «библиотека собралась, но не грузится».**
> 1. `CANNOT LINK EXECUTABLE virgl_test_server: cannot locate symbol "__android_log_print"`.
>    Мой шим `log/log.h` (в NDK нет AOSP-шного заголовка) отображал `LOG_PRI` в
>    `__android_log_print`, а `liblog` никто не линковал. В ELF это не ошибка: у `.so` по
>    умолчанию разрешены неразрешённые символы, поэтому линковка прошла, а `dlopen` умер —
>    и вместе с ним весь сервер. Теперь шим печатает в `stderr` (app и так ловит его как
>    `[virgl]`), плюс включён `b_lundef=true` (`-Wl,--no-undefined`), чтобы такое падало на
>    сборке. Проверка: `readelf -W --dyn-syms libvirglrenderer.so | grep UND` — только bionic+epoxy.
> 2. `glxinfo: symbol lookup error: /opt/gl4es/lib/libGL.so.1: undefined symbol:
>    ConvertShaderConditionally`. В CMake-сборке форка Holy-GL4ES **забыт
>    `src/gl/vgpu/shaderconv.c`** (в `Android.mk` он есть), поэтому вся vgpu-часть
>    (backport/forward-port шейдеров) отсутствовала. `build-gl4es.sh` теперь добавляет файл
>    в `src/CMakeLists.txt` и линкует с `-Wl,--no-undefined`.
> Плюс `OVERLAY_VERSION` поднят до 12 — иначе новый `libGL.so.1` не доехал бы до устройства
> (overlay 11 уже был применён), и `SETUP_VERSION` до 11 ради новых бинарников сервера.
> Из прогона 0.2.0 полезное: gl4es-путь доходит до загрузки `libGL.so.1` из `/opt/gl4es/lib`,
> `libvulkan.so.1` копируется (`copied /system/lib64/libvulkan.so`), env-ы бэкендов доезжают.

> **0.2.0 — четыре бэкенда с явным выбором в настройках.**
> Автоматическая цепочка с автопереключением убрана: теперь в «Настройки → Графика» четыре
> радио-кнопки, выбор хранится в `renderer` (`cpu` / `virgl` / `zink` / `gl4es`). Если бэкенд
> умирает раньше 30 с, приложение только пишет об этом в лог, ничего не переключая — иначе
> невозможно сравнивать варианты.
>
> | Значение | Что происходит | Хост-сервер | Гость |
> |---|---|---|---|
> | `cpu` | llvmpipe | не запускается | `GALLIUM_DRIVER=llvmpipe`, `LIBGL_ALWAYS_SOFTWARE=1` |
> | `virgl` | Mesa virpipe → virglrenderer → Mali GLES | `virgl_test_server --use-gles` | `GALLIUM_DRIVER=virpipe`, caps 4.3 |
> | `zink` | zink → Vulkan → Venus-протокол → драйвер Vulkan Mali | `virgl_test_server --venus` | `GALLIUM_DRIVER=zink`, `VK_ICD_FILENAMES=…/virtio_icd.json`, `VTEST_SOCKET_NAME=/tmp/.virgl_test` |
> | `gl4es` | Holy GL4ES (OpenGL 2.1 → GLES) внутри гостя, поверх того же virpipe | `virgl_test_server --use-gles` | `LD_LIBRARY_PATH=/opt/gl4es/lib`, `LIBGL_ES=2`, `LIBGL_FB=3`, `SDL_OPENGL_LIBRARY=/opt/gl4es/lib/libGL.so.1` |
>
> **Holy GL4ES** (`tools/build-gl4es.sh`): форк `FCL-Team/Holy-GL4ES`, ветка `1.1.4-Holy`,
> собран для glibc-aarch64 тулчейном Bootlin (NDK не годится — гость на glibc). В overlay
> добавлены `/opt/gl4es/lib/libGL.so{,.1}`; GLIBC-версии сверены (нужна ≤ glibc рутфса, сейчас
> ровно 2.43 = 2.43). В `run.sh` принудительно включён GLX-путь SDL (`SDL_VIDEO_X11_FORCE_EGL=0`
> + `SDL_OPENGL_LIBRARY`), иначе SDL идёт в EGL мимо GL4ES. `LIBGL_VERSION=3.3` — осознанная
> ложь, иначе Factorio 2.x не стартует вообще (GL4ES честно умеет 2.1). `LIBGL_FB=3` +
> `EGL_PLATFORM=surfaceless`, потому что virpipe в контейнере не даёт X11-window surface.
> **Ожидание:** этот путь скорее всего не заведётся — Factorio нужен GL 3.3 (VAO/UBO/MRT),
> а GL4ES реализует 2.1; ветка «1/8 от 3.x». Если нужен настоящий тонкий враппер 3.3 —
> в той же семье есть LTW, его можно поставить в эту же ячейку вместо GL4ES.
>
> Остальное из 0.1.24 в силе (virglrenderer c `check-gl-errors=false`,
> `--use-gles`, venus в режиме `thread`). `OVERLAY_VERSION` = 11, `SETUP_VERSION` = 10,
> `cfg_version` = 5 (`software`→`cpu`, `gpu`→`virgl`, `gpu_stage` удалён).
> Новые детали сборки: `tools/build-overlay.sh` (пересобрать overlay) и `tools/build-gl4es.sh`.

> **0.1.24 — virglrenderer собран под NDK (meson), причина краша GPU найдена в исходниках.**
> Сборка: `tools/build-virgl.sh` (см. ниже) — `libepoxy` + `virglrenderer` (HEAD 1.3.0,
> NDK 29, `--cross-file`, `system='android'`, API 30). Отличия от апстрима, все вынужденные:
> 1. **EGL без GBM/libdrm.** В апстриме `ENABLE_GBM` включается только при наличии libdrm+gbm,
>    а `vrend_winsys_init()` без `ENABLE_GBM` просто пишет «EGL is not supported». Патч в
>    `meson.build` (android → `have_egl = true`) + ветка `#elif defined(HAVE_EPOXY_EGL_H)` в
>    `vrend_winsys.c` на `virgl_egl_init(EGL_DEFAULT_DISPLAY, …)`.
> 2. **`-Dcheck-gl-errors=false`.** Вот и настоящая причина всех падений: в
>    `vrend_decode_ctx_submit_cmd()` (`src/vrend/vrend_decode.c:2111`) любая хостовая GL-ошибка
>    превращается в `EINVAL` → `failed to dispatch TRANSFER3D: 22` → `Illegal command buffer` →
>    `VTEST_CLIENT_ERROR_COMMAND_DISPATCH` → Factorio ловит SIGABRT. То есть `GL_INVALID_VALUE`
>    от Mali был **не фатален**, фатальной его делала проверка. Никакого рассинхрона протокола
>    Mesa 26.2 ↔ virglrenderer не было: гость и хост одинаково считают `VIRGL_TRANSFER3D_SIZE = 13`.
>    С выключенной проверкой ошибки остаются предупреждениями (`GL error reported (1281)…`).
> 3. **`--venus` с `render-server-mode=thread`** — Venus теперь поднимается внутри процесса
>    сервера. В пакете Termux режим `process`, то есть нужен отдельный бинарник
>    `virgl_render_server`, который там не запускается — почти наверняка именно поэтому был
>    `VTEST_CLIENT_ERROR_CONTEXT_FAILED`. Плюс `Setup` кладёт рядом копию
>    `/system/lib64/libvulkan.so` под именем **`libvulkan.so.1`** (именно его `dlopen`-ит venus,
>    а `LD_LIBRARY_PATH` линкер Android для app-процессов игнорирует).
>
> Сервер теперь всегда запускается с **`--use-gles`**: Android/mali отдаёт через EGL только
> GLES, а апстрим по умолчанию просит desktop-GL контекст (`eglBindAPI(EGL_OPENGL_API)` там
> невозможен). ANGLE-ступень убрана: `--angle-vulkan` есть только в форке Termux, в апстриме
> его нет. Цепочка: **0 = host GLES (гость видит GL 4.3) → 1 = те же caps как 3.3 → 2 = zink/Venus
> → софт**. `SETUP_VERSION` = 10, `cfg_version` = 4 (старые `gpu_stage` 3/4 сбрасываются в 0,
> а если прошлая цепочка успела переключить приложение в software — возвращаем `gpu`).
> `lint { checkReleaseBuilds false }`: AGP-линт валится на обязательном `targetSdkVersion 28`.

> **0.1.23**: ANGLE-ступень падала, потому что путь к ANGLE **зашит в `virgl_test_server_android`**
> (`/data/data/com.termux/files/usr/opt/angle-android/*`). Пути (vulkan, vulkan-null, gl) перезаписаны
> прямо в ELF на `/data/data/io.github.factorioandroid/files/av` (NUL-паддинг), ANGLE-библиотеки
> кладутся туда же (`assets/angle` → `files/av`). `SETUP_VERSION` = 9.
>
> **0.1.22**: Venus на стороне virglrenderer не поднимается — сервер отвечает
> `VTEST_CLIENT_ERROR_CONTEXT_FAILED` (гость падает в `vkCreateInstance`). Добавлена четвёртая
> ступень: **virgl + `--angle-vulkan`** — тот же virgl-протокол, но хост-сторона GLES идёт через
> ANGLE→Vulkan→Mali (ANGLE-библиотеки лежат в assets под своими именами `lib*_angle.so`, без
> подмены системных). Цепочка: 4.3 → 3.3 → zink/Venus → virgl+ANGLE → софт.
>
> **0.1.21**: Venus-ICD грузится (после добавления libxcb-keysyms), но `vkEnumeratePhysicalDevices`
> даёт `Failed to detect any valid GPUs` — драйвер шёл в DRM (`/dev/dri`), которого нет.
> Добавлен `VTEST_SOCKET_NAME=/tmp/.virgl_test` — по этой переменной Mesa выбирает vtest-backend.
>
> **0.1.20 — причина падения Venus найдена**: `libvulkan_virtio.so` не загружался, т.к. в rootfs
> не было `libxcb-keysyms.so.1` → Vulkan-loader писал `Found no drivers!` → zink падал с
> `VK_ERROR_INCOMPATIBLE_DRIVER`. В overlay добавлены `libxcb-keysyms.so.1`, `libdisplay-info.so.3`.
> Плюс правка config.ini стала идемпотентной (раньше вставляла строки при каждом запуске и в итоге
> игра ругалась `Bad file format`).
>
> **0.1.19**: Venus-попытка дала `zink: vkCreateInstance failed (VK_ERROR_INCOMPATIBLE_DRIVER)`.
> Теперь сервер virgl **всегда** запускается с `--venus` (для virpipe безвредно), в лог пишется его
> командная строка, а гостю выставлены `VK_LOADER_DEBUG=error,warn` и `VN_DEBUG=init`, чтобы видеть
> причину. Если и это не заведётся — остаётся собрать новый virglrenderer под NDK.
>
> **0.1.18**: GL 4.3 и 3.3 падают ОДИНАКОВО (`TRANSFER3D`, GL_INVALID_VALUE) — значит дело не в
> версии GL и не в текстурах (streaming/compression/виртуальный атлас уже отключены), а в самом
> virglrenderer 1.3.0 против Mesa 26.2. Добавлена третья ступень автоподбора — **zink поверх Venus**
> (Vulkan-протокол по тому же сокету virgl): в overlay лежат `libvulkan.so.1` + `libvulkan_virtio.so`
> + `virtio_icd.json`, сервер запускается с `--venus`, гость — `GALLIUM_DRIVER=zink`,
> `VK_ICD_FILENAMES=/usr/share/vulkan/icd.d/virtio_icd.json`. Цепочка: 4.3 → 3.3 → zink/Venus → софт.
>
> **0.1.17**: выяснилось, что без override контекст вообще не создаётся (`GLXBadFBConfig`), т.е.
> virgl отдаёт < 4.5, а с 4.5 ломается на TRANSFER3D. Теперь цепочка автоподбора в GPU-режиме:
> **GL 4.3 → GL 3.3 → софт** (при быстром падении приложение само перезапускает игру со следующей
> ступенью и пишет это в лог). Плюс config.ini теперь правится правильно — ключи там
> закомментированы (`; texture-streaming=true`), поэтому ранний sed не срабатывал; теперь в
> секцию `[graphics]` вставляются `texture-streaming=false`, `texture-compression-level=none`,
> `compress-virtual-atlas=false`.
>
> **0.1.15**: главная гипотеза по GPU-крашу — мы принудительно включали
> `MESA_GL_VERSION_OVERRIDE=4.5` и для virgl, хотя хост-драйвер Mali даёт GLES 3.2.
> Mesa начинала использовать GL 4.5-пути (в частности transfer/storage), которые virglrenderer
> не умеет → `GL_INVALID_VALUE` на TRANSFER3D. Теперь override ставится **только** в софт-режиме.
> Плюс в GPU-режиме config.ini правится надёжнее (пробелы в ключах), а сам config.ini теперь
> целиком выводится в лог — видно точные имена и значения параметров текстур.
>
> **0.1.14 — GPU-контекст создаётся на реальном железе**:
> `Initialised OpenGL: virgl (Mali-G925-Immortalis MC11); 4.5 Core` — virglrenderer → системный
> EGL → Mali работает. Но затем `vrend_check_no_error: Unknown 1281` на `TRANSFER3D`
> (GL_INVALID_VALUE при загрузке текстур) → virglrenderer рвёт клиента → краш Factorio.
> В GPU-режиме `run.sh` теперь выключает `texture-streaming`/`texture-compression` в config.ini
> (пробуем обойти битый путь передачи), а приложение **авто-откатывается** на софт-рендер,
> если GPU-запуск умер в первые 30 секунд (чтобы не ловить краш каждый раз).
>
> **0.1.13**: ANGLE из APK убран совсем — его `libEGL/libGLESv2` являются *плагинами* для
> системного EGL-загрузчика Android, и подмена системного `libEGL.so` ломает
> `libandroid_runtime.so` (`cannot locate symbol android::egl_get_init_count`). Теперь libepoxy
> в virgl-сервере резолвит `libEGL.so` в системный = настоящий драйвер Mali/Adreno.
> Плюс `Setup` теперь **полностью очищает** `files/android/` перед распаковкой — иначе старые
> .so из предыдущих версий продолжали затенять системные библиотеки.
>
> **0.1.12**: звук заработал (`[Audio] Driver:pulseaudio`), X-экран 1744x853. GPU-режим падал:
> мы клали `libGLESv1_CM.so` (ANGLE) в каталог virgl, перекрывая системную библиотеку того же
> имени, которую линкер Android использует для `libandroid_runtime.so` → SIGSEGV в ANGLE.
> Эта копия убрана (GLES1 не нужен). Дефолтные аргументы игры теперь
> `--max-texture-size=2048 --video-memory-usage=low`.
>
> **0.1.11 — ИГРА ЗАПУСТИЛАСЬ** (llvmpipe, GL 4.5 core, атласы построены, `Factorio initialised`).
> Осталось: звук и скорость.
> * Звук: в rootfs не было `libpulse.so.0` → SDL падал на ALSA («cannot find card 0»). Через overlay
>   добавлены `libpulse`, `libpulsecommon`, `libsndfile`, `libsoxr`, `libvorbis`, `libasyncns` и т.д.;
>   `run.sh` выставляет `SDL_AUDIODRIVER=pulseaudio` + `PULSE_SERVER=tcp:127.0.0.1:4713`.
> * Скорость: X-экран теперь масштабируется (`displayResolutionMode=scaled`, `displayScale=150`
>   по умолчанию → ~1744x853 вместо 2616x1280), в аргументах по умолчанию
>   `--max-texture-size=2048`. В настройках приложения появился выбор разрешения.
>
> **0.1.10 — НАЙДЕНА ПРИЧИНА «GLX visual not found»**: в `tools/prune-rootfs.sh` я вырезал
> `libicu*`, а цепочка `libgallium → libLLVM → libxml2 → libicuuc.so.78` без ICU не грузится
> вообще, поэтому Mesa не могла поднять ни swrast, ни fbconfigs. `libicuuc`/`libicudata`
> возвращены через overlay (v4, 10 МБ) и prune-скрипт исправлен. Проверка зависимостей
> mesa-стека теперь чистая (кроме системных libc/libm).
>
> **0.1.7/0.1.8**: игра создаёт окно, но GLX мёртв: `xdpyinfo -ext GLX` → «not supported»,
> `glxinfo -B` → «couldn't find RGB GLX visual or fbconfig». То есть расширение GLX
> зарегистрировано, но вендор не привязан к экрану (в `GlxExtensionInit` срабатывает
> `extEntry->base = 0`). Добавлено: `XServerService` перенаправляет **stderr X-сервера** в
> `files/logs/x11.log`, лог показывает его первым блоком; в код xserver (наш submodule)
> вставлены `ErrorF("LORIE: GLX: …")` во все «молчаливые» ветки инициализации GLX
> (`GlxExtensionInit`, `xorgGlxServerPreInit`, `createVendor`, probe экрана), плюс вывод
> количества X-визуалов и сгенерированных fbconfigs. Ждём лог, чтобы выбрать фикс.

> **0.1.6**: игра запустилась, дошла до создания окна и упала на
> `SDL_Error: Couldn't find matching GLX visual` (X11/proot/окно работают!). Добавлен механизм **overlay**: маленький `assets/overlay.tar.zst` (127 КБ, версия в `overlay.version`) накатывается
> поверх rootfs — так можно править run.sh и докладывать утилиты без пересборки базы.
> В overlay: `glxinfo`, `glxgears`, `xdpyinfo` + обновлённый `run.sh`, который при `DIAG=1`
> печатает в лог окружение, список `/usr/lib/dri`, `glxinfo -B` (с `LIBGL_DEBUG=verbose`),
> `xdpyinfo -ext GLX` и таблицу визуалов.

> **0.1.5**: proot стартовал, но `execve("/usr/bin/bash"): Permission denied` — proot использует
> свой `libexec/loader` как интерпретатор для трассируемых ELF, а он копировался с режимом 0644.
> Теперь каталог `proot` извлекается как исполняемый (0755), в лог пишется результат
> `access(loader, X_OK)`. `SETUP_VERSION` = "4".

> **0.1.4**: X-сервер стартует (`start() returned true`). Дальше падали proot и pulseaudio с
> `CANNOT LINK EXECUTABLE ... libtalloc.so.2 not found`. Причин две:
> 1) `cp -a` копировал `libtalloc.so.2` как **битый симлинк** на `libtalloc.so.2.5.0`;
> 2) Android-линкер игнорирует `LD_LIBRARY_PATH` у процессов приложения — Termux решает это
>    через `DT_RUNPATH`. Теперь `tools/stage-assets.sh` копирует с `cp -aL` и прогоняет
>    `patchelf --set-rpath /data/user/0/io.github.factorioandroid/files/android/<dir>` по всем
>    бинарникам и .so. Также добавлены `libGLESv2_angle.so`/`libEGL_angle.so` для ANGLE.
> `SETUP_VERSION` = "3" (хелперы перекопируются, rootfs не перерисовывается).

> **0.1.3**: X-сервер падал сразу после старта. Причина найдена в `cmdentrypoint.cpp`:
> `XkbBaseDirectory = getenv("XKB_CONFIG_ROOT"); if (access(XkbBaseDirectory, F_OK) != 0) return JNI_FALSE;`
> а `<rootfs>/usr/share/X11/xkb` — **абсолютный симлинк** на `/usr/share/xkeyboard-config-2`,
> которого на Android нет. Теперь передаём настоящий каталог `<rootfs>/usr/share/xkeyboard-config-2`.
> Плюс: в proot добавлен bind `<tmp>/shm:/dev/shm`, логируется код выхода процессов и
> проверяемые пути (TMPDIR/XKB) при старте X-сервера.

> **0.1.2**: `app_process` (запуск X-сервера как в Termux) на Android 16/HyperOS падает молча.
> X-сервер теперь живёт в **собственном процессе приложения** `:x11` (`XServerService`,
> foreground) и стартует через `com.termux.x11.FactorioX11` (bridge в пакет библиотеки:
> `System.loadLibrary("Xlorie")` + `new CmdEntryPoint(args)`), без Unsafe/ActivityThread.
> Лог теперь читается из общего файла, поэтому сообщения процесса `:x11` тоже видны в UI.

> **0.1.1**: распаковка архива больше не зависит от внешних бинарников Termux (`zstd`/`tar`
> на устройстве не запускались). Теперь zstd 1.5.7 собран в APK как `libzstdjni.so`
> (только декомпрессор) + тар-парсер на Java (`TarExtractor` + `Fs`/`AndroidFs`).
> Весь путь проверен локально на реальном архиве: 2 020 567 040 байт, 18 477 файлов,
> 1481 симлинк, права/размеры совпадают с исходным rootfs. Все сообщения этапов
> (setup/x11/virgl/audio/game) теперь идут в экранный лог (`Logs`), а не только в logcat.

---

## Что уже готово

| Компонент | Статус |
|---|---|
| X11-сервер `lorie` (Termux:X11) под NDK | ✅ собирается и упакован (`lib/arm64-v8a/libXlorie.so`) |
| rootfs Arch Linux ARM + Mesa (llvmpipe/zink/virpipe) + X11-библиотеки | ✅ `app/src/main/assets/rootfs.tar.zst` (1.547 ГБ) |
| Factorio 2.1.24 внутри rootfs, бинарь **stripped** (261 → 65 МБ) | ✅ проверено под qemu: `Version: 2.1.24 (build 87808, linux-arm64, full)` |
| proot (Termux) + loader | ✅ `assets/proot/*` |
| распаковка при первом запуске: JNI zstd + Java tar | ✅ проверено локально (см. выше) |
| Java-лончер (прогресс, настройки, лог) | ✅ `app/src/main/java/io/github/factorioandroid/` |
| Запуск X-сервера через `app_process … CmdEntryPoint :0` | ✅ код в `Game.startX()` (на устройстве не проверено) |
| Звук: PulseAudio (Termux) + `module-sles-sink` (OpenSL ES) | ✅ упакован, на устройстве не проверено |
| GPU: свой `virglrenderer` (NDK) + `libepoxy`, клиент `GALLIUM_DRIVER=virpipe`/`zink` | ⚠️ собрано и упаковано, на устройстве ещё не подтверждено |
| APK: подпись debug, zipalign, targetSdk 28, extractNativeLibs | ✅ |

## Как тестировать

```bash
adb install -r FactorioAndroid-0.3.1-arm64.apk     # нужен ~4.5 ГБ свободного места
adb logcat -s FactorioAndroid CmdEntryPoint LorieApp lorie:V tx11-request
```
1. Запустить приложение → «Установить данные игры» (распаковка 1–5 мин).
2. Дальше сразу стартует X-сервер и игра.
3. Кнопка «Лог» на стартовом экране — журнал всех процессов (копируется в буфер).

Если чёрный экран / игра не стартует: Настройки → «Программная (llvmpipe)» (по умолчанию уже она),
плюс галочка «Режим совместимости proot».

Для GPU: Настройки → «Графика» → **VirGL**. Ничего не переключается само: если бэкенд упал,
в логе будет строка `renderer 'virgl' died after N ms`.

Экранное управление: галочка «Экранное управление» в тех же настройках включает оверлей-геймпад
в окне X-сервера. Раскладка — `app/src/main/res/raw/controls_default.json`, кнопка «Сбросить
раскладку» возвращает стандартную. Проведения пальцем вне кнопок работают как тачпад.

## Где что лежит

| Путь | Что |
|---|---|
| `FactorioAndroid-0.3.1-arm64.apk` / `-dev.apk` | готовые APK |
| `app/` | модуль приложения (Java + манифест + assets) |
| `lorie/` | X-сервер (форк termux-x11, GPLv3) |
| `stub/` | стабы скрытого Android API (нужны для компиляции `CmdEntryPoint`) |
| `tools/build-rootfs.sh` | сборка rootfs (Arch ARM, pacman в user namespace, без root) |
| `tools/prune-rootfs.sh` | вычищает прошивки/документацию/лишние бинарники (3.6 ГБ → 2.0 ГБ) |
| `tools/stage-assets.sh` | раскладывает Termux-бинарники в `app/src/main/assets` (с `VIRGL_BUILD=…` — свою сборку virglrenderer) |
| `tools/build-virgl.sh` | сборка libepoxy + virglrenderer под Android через meson/NDK |
| `tools/build-overlay.sh` | пересборка `overlay.tar.zst` из `$OVERLAY_STAGE` |
| `app/src/main/res/raw/controls_default.json` | раскладка экранного геймпада (позиции в долях экрана, действия) |
| `lorie/.../controls/` | оверлей управления: `ControlsSpec` (парсер JSON), `ButtonSpec`, `TouchControlsView` |
| `tools/gettermux.py` | скачивает пакеты Termux (proot, virgl, pulseaudio) |
| `tools/termux-root/` | распакованные Termux-бинарники |
| `../.work/rootfs` | распакованный rootfs (2.0 ГБ), `../.work/rootfs.tar(.zst)` |
| `../.android-sdk` | SDK + NDK 29 |

## Сборка с нуля

```bash
export JAVA_HOME=/usr/lib/jvm/java-17-openjdk
export ANDROID_HOME=$PWD/../.android-sdk
python3 tools/gettermux.py proot virglrenderer-android pulseaudio
WORK=$PWD/../.work bash tools/build-rootfs.sh ../factorio
unshare -r bash tools/prune-rootfs.sh ../.work/rootfs
tar -C ../.work/rootfs -cf ../.work/rootfs.tar . && zstd -T16 -12 ../.work/rootfs.tar -o app/src/main/assets/rootfs.tar.zst
bash tools/build-virgl.sh          # -> /tmp/opencode/fp/{vr-build,andeps}
VIRGL_BUILD=/tmp/opencode/fp/vr-build EPOXY_BUILD=/tmp/opencode/fp/andeps \
  PATCHELF=/tmp/opencode/fp/pvenv/bin/patchelf bash tools/stage-assets.sh
bash tools/build-overlay.sh        # -> app/src/main/assets/overlay.tar.zst
./gradlew --no-daemon --no-configuration-cache :app:assembleRelease
```

## Грабли, на которые уже наступили (не повторять)

* `settings.gradle` не должен включать `:shell-loader` — сам факт `include ':shell-loader:stub'`
  создаёт родительский проект `:shell-loader`, и Gradle подхватывает `shell-loader/build.gradle`
  (модуль Termux-обвязки, ему нужен `:lorie-app`). Поэтому `stub` перенесён в корень: `:stub`.
* AGP 9: в `lorie/build.gradle` `compileSdk` выставляется **до** `evaluationDependsOn(...)`,
  иначе падает «does not specify compileSdk».
* `tools/build-rootfs.sh` перезапускает себя в `unshare -r`: Pacman требует euid 0, а архивы
  содержат файлы с правами root. Извлечение архива — с `--no-same-owner` (иначе chown в userns падает).
* `/usr/sbin` в Arch — симлинк на `bin`. Чистка `sbin/*` удаляет все бинарники (уже один раз случилось).
* `data/doc-html` (332 МБ) в APK не кладём; `boot/` базового тарбола (193 МБ) тоже выкинут.
* APK > 2 ГБ Android не устанавливает — поэтому `rootfs.tar.zst` (zstd -12), а не сырой tar (2.02 ГБ).
* При пересборке APK с изменённым asset'ом Gradle иногда оставляет «дыру» в zip (было +62 МБ):
  помогает `rm -rf app/build/intermediates/apk app/build/outputs/apk` перед сборкой.
* AGP 9 `assembleRelease` теперь гоняет lint и падает на `ExpiredTargetSdkVersion`
  (targetSdk 28 обязателен) — поэтому `lint { checkReleaseBuilds false; abortOnError false }`.
* Сборка virglrenderer: mesa-файлы `os_misc.c` тянут AOSP-шные `<log/log.h>` и
  `<cutils/properties.h>`, которых в NDK нет — в `tools/build-virgl.sh` кладутся шимы
  в `$WORK/compat-android` (через `<android/log.h>` и `<sys/system_properties.h>`).
* `meson` не находит venus-protocol → тянет сабпроект и требует python-модули `mako` и `pyyaml`
  в *том* python3, который первым в `PATH`.
* `pkg-config` для NDK: у NDK нет `.pc` для EGL/GLESv2, `build-virgl.sh` их генерирует.
  Без них libepoxy собирается без EGL (`epoxy_has_egl=0`) и virglrenderer вообще не получает
  EGL-платформу (собирается «только venus», vrend отключается).

## Что осталось / что проверить на устройстве

1. Стартует ли `app_process` (SELinux): подтверждение — в логе строка `[x11] starting X server` и
   появление `<files>/tmp/.X11-unix/X0`. Если нет — смотреть `adb logcat` на `avc: denied`.
2. Доходит ли Factorio до окна в llvmpipe (нужен DRI3, в Lorie он есть).
3. **GPU.** Для `virgl`/`gl4es` в логе по порядку:
   * `[virgl] starting virgl server: … --use-gles` — новая команда, старого `--angle-vulkan` быть не должно;
   * `[virgl] GLSL feature level 430` (или 400) — сервер реально создал Mali-контекст;
   * `GL error reported (1281)` вместо `failed to dispatch TRANSFER3D` — значит проверка
     ошибок действительно больше не убивает контекст;
   * `Initialised OpenGL:[1] virgl (Mali-G925-Immortalis MC11)` и дальше **нет** `Factorio crashed`;
   * если всё же `--venus`: `[virgl] libvulkan.so.1`/`VN_DEBUG` и `VTEST_CLIENT_ERROR_CONTEXT_FAILED`.
   Если картинка есть, но текстуры «мусор» — это те самые GL_INVALID_VALUE, включать
   `VIRGL_LOG_LEVEL=debug` + `VREND_DEBUG=tex` (в `Game.startVirgl`) и смотреть формат/уровень.
   * `gl4es`: в логе `----- diag: gl4es -----` должен показать `libGL.so.1` из `/opt/gl4es/lib`,
     дальше баннер GL4ES отключён; при ошибке — `LIBGL: loaded: …`/`Error: …`. Скорее всего
     Factorio всё равно откажется: у GL4ES нет GL 3.3 (VAO/UBO/MRT). Тогда в `run.sh` убрать
     `LIBGL_VERSION=3.3`, чтобы увидеть честное сообщение, и/или поставить LTW в эту же ячейку.
   * `zink`: `[virgl] starting virgl server: … --venus`, затем `VN_DEBUG=init` в логе игры.
     Если `VTEST_CLIENT_ERROR_CONTEXT_FAILED` — venus не поднялся, смотреть `libvulkan.so.1`
     в `<files>/android/virgl/` (Setup кладёт туда копию `/system/lib64/libvulkan.so`).
4. Звук: `module-sles-sink` поднимается ли на конкретном устройстве.
5. Скорость первого запуска (сборка атласов) и FPS.
6. Возможные доработки: экранные кнопки под Factorio (сейчас — тачпад-эмуляция Lorie + панель
   доп. клавиш), импорт своих сохранений из `/sdcard`, авто-настройка `--video-memory-usage`.
