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

BUILD_DIR ?= ../build/pct
OUT := $(BUILD_DIR)

# One build per target build: the profile gate, the direct-map addresses and the
# kallsyms-derived links are all baked in, so a source tree only ever serves the
# fingerprint it was ported to (see each subdirectory's NOTICE).
CFLAGS := -O2 -fno-stack-protector
# 16 KB page kernels are the default on newer Android releases.
LDFLAGS := -Wl,-z,max-page-size=16384

.PHONY: all payload clean

all: payload

payload: $(OUT)/libpct-opace6.so

# OnePlus Ace 6 (OP6113L1, 6.6.118 GKI), upstream Wangs-official/opace6-cve-2026-64560.
$(OUT)/libpct-opace6.so: opace6/exploit-fanout-opace6.c
	@mkdir -p $(OUT)
	$(CC) $(CFLAGS) opace6/exploit-fanout-opace6.c -o $@ $(LDFLAGS)
	$(STRIP) $@

clean:
	rm -rf $(OUT)
