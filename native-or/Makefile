API ?= 35

# Auto-detect NDK (same rules as ../native/Makefile).
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

BUILD_DIR ?= ../build/or
OUT := $(BUILD_DIR)

# splices/ko images are pulled in with `.incbin`, so they are resolved relative to
# this directory and must exist before exp.c is assembled.
KO_IMAGES := $(wildcard ko/*.ko)
CFLAGS := -O2 -I. -fno-stack-protector -fomit-frame-pointer
LDFLAGS := -Wl,-z,max-page-size=16384

.PHONY: all clean payload

all: payload

payload: $(OUT)/libopenroot.so $(OUT)/libopenbootstrap.so

# crash_dump64 replacement: freestanding static binary, embedded into exp.c.
splicehelper: splicehelper.c
	$(CC) splicehelper.c -o $@ -nodefaultlibs -nostartfiles -ffreestanding -static
	$(STRIP) $@

$(OUT)/libopenroot.so: exp.c libcxx.S elf_parser.c include.inc aes256.h splicehelper $(KO_IMAGES)
	@mkdir -p $(OUT)
	$(CC) $(CFLAGS) exp.c libcxx.S elf_parser.c -o $@ -llog $(LDFLAGS)
	$(STRIP) $@

$(OUT)/libopenbootstrap.so: bootstrap.c
	@mkdir -p $(OUT)
	$(CC) $(CFLAGS) bootstrap.c -o $@ $(LDFLAGS)
	$(STRIP) $@

clean:
	rm -f splicehelper
	rm -rf $(OUT)
