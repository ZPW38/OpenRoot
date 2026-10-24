API ?= 24

# Auto-detect NDK (same rules as ../native-or/Makefile).
ifeq ($(OS),Windows_NT)
  NDK_ROOT ?= $(subst \,/,$(or $(ANDROID_NDK_HOME),$(NDK_ROOT_ENV),D:/android-ndk-r27d))
  override NDK_ROOT := $(subst \,/,$(NDK_ROOT))
  PREBUILT := windows-x86_64
  EXT := .cmd
  STRIP_EXT := .exe
else
  NDK_ROOT ?= $(or $(ANDROID_NDK_HOME),$(ANDROID_NDK_ROOT),/opt/android-ndk-r27d)
  UNAME_S := $(shell uname -s)
  PREBUILT := $(if $(filter Darwin,$(UNAME_S)),darwin-x86_64,linux-x86_64)
  EXT :=
  STRIP_EXT :=
endif

NDK_BIN := $(NDK_ROOT)/toolchains/llvm/prebuilt/$(PREBUILT)/bin
CC := $(NDK_BIN)/aarch64-linux-android$(API)-clang$(EXT)
STRIP := $(NDK_BIN)/llvm-strip$(STRIP_EXT)

BUILD_DIR ?= ../build/gpu
OUT := $(BUILD_DIR)

# The exploits are plain C programs that include their helpers by name, so each
# device family gets its own directory rather than sharing one flat source dir.
CFLAGS := -O2
# 16 KB page kernels are the default on newer Android releases.
LDFLAGS := -Wl,-z,max-page-size=16384

.PHONY: all payload clean

all: payload

payload: $(OUT)/libcheese-quest.so $(OUT)/libcheese-snapdragon.so

# Quest 3 / 3S build (Adreno 740 / XR2 Gen 2), upstream zhuowei/cheese.
$(OUT)/libcheese-quest.so: quest/cheese.c quest/adrenaline.h quest/kallsyms_lookup.c
	@mkdir -p $(OUT)
	$(CC) $(CFLAGS) quest/cheese.c -o $@ $(LDFLAGS)
	$(STRIP) $@

# Snapdragon 8 (Gen 1 / 8+ Gen 1) port, cve-2025-21479_iqooneo8.
$(OUT)/libcheese-snapdragon.so: snapdragon/cheese.c snapdragon/adrenaline.h snapdragon/kallsyms_lookup.c
	@mkdir -p $(OUT)
	$(CC) $(CFLAGS) snapdragon/cheese.c -o $@ $(LDFLAGS)
	$(STRIP) $@

clean:
	rm -rf $(OUT)
