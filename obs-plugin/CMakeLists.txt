cmake_minimum_required(VERSION 3.22)

project(obs-layercast VERSION 1.0.0 LANGUAGES C)

# The plugin only uses the stable C API of libobs, so it needs the public headers of a matching
# obs-studio release plus the libobs binary of the installed OBS to link against.
set(OBS_VERSION "32.2.2" CACHE STRING "obs-studio release whose headers are used")
set(OBS_SOURCE_DIR "" CACHE PATH "Existing obs-studio source checkout (optional; downloaded otherwise)")
if(APPLE)
  set(OBS_APP "/Applications/OBS.app" CACHE PATH "OBS.app to link against")
elseif(WIN32)
  set(OBS_DIR "C:/Program Files/obs-studio" CACHE PATH "OBS installation to link against")
else()
  set(OBS_LIBRARY "" CACHE FILEPATH "libobs.so to link against (default: search system)")
endif()

if(NOT OBS_SOURCE_DIR)
  include(FetchContent)
  FetchContent_Declare(
    obs_studio_headers
    GIT_REPOSITORY https://github.com/obsproject/obs-studio.git
    GIT_TAG ${OBS_VERSION}
    GIT_SHALLOW TRUE
    GIT_SUBMODULES "")
  FetchContent_GetProperties(obs_studio_headers)
  if(NOT obs_studio_headers_POPULATED)
    FetchContent_Populate(obs_studio_headers)
  endif()
  set(OBS_SOURCE_DIR "${obs_studio_headers_SOURCE_DIR}")
endif()

# On non-x86 targets libobs' math headers map SSE intrinsics through SIMDe (header only).
set(SIMDE_DIR "" CACHE PATH "SIMDe checkout (optional; downloaded otherwise)")
if(NOT SIMDE_DIR)
  include(FetchContent)
  FetchContent_Declare(simde GIT_REPOSITORY https://github.com/simd-everywhere/simde.git GIT_TAG v0.8.2 GIT_SHALLOW TRUE)
  FetchContent_GetProperties(simde)
  if(NOT simde_POPULATED)
    FetchContent_Populate(simde)
  endif()
  set(SIMDE_DIR "${simde_SOURCE_DIR}")
endif()

# libobs includes a CMake-generated obsconfig.h; plugins only need the release flags.
set(GENERATED_DIR "${CMAKE_CURRENT_BINARY_DIR}/generated")
file(WRITE "${GENERATED_DIR}/obsconfig.h" "#pragma once\n#define OBS_RELEASE_CANDIDATE 0\n#define OBS_BETA 0\n")

add_library(obs-layercast MODULE
  src/plugin-main.c
  src/layercast-source.c
  src/lc-directory.c)
target_include_directories(obs-layercast PRIVATE src "${OBS_SOURCE_DIR}/libobs" "${GENERATED_DIR}" "${SIMDE_DIR}")
set_target_properties(obs-layercast PROPERTIES C_STANDARD 11 C_STANDARD_REQUIRED ON PREFIX "")

if(MSVC)
  target_compile_options(obs-layercast PRIVATE /W3 /utf-8)
else()
  target_compile_options(obs-layercast PRIVATE -Wall -Wextra -Wno-unused-parameter -Wno-missing-field-initializers)
endif()

set(PLUGIN_DATA "${CMAKE_CURRENT_SOURCE_DIR}/data")

if(APPLE)
  target_sources(obs-layercast PRIVATE src/lc-texture-macos.c)
  target_link_libraries(obs-layercast PRIVATE
    "-F${OBS_APP}/Contents/Frameworks" "-framework libobs"
    "-framework IOSurface" "-framework CoreFoundation")
  configure_file(cmake/Info.plist.in "${CMAKE_CURRENT_BINARY_DIR}/Info.plist" @ONLY)
  set_target_properties(obs-layercast PROPERTIES
    BUNDLE TRUE
    BUNDLE_EXTENSION plugin
    MACOSX_BUNDLE_INFO_PLIST "${CMAKE_CURRENT_BINARY_DIR}/Info.plist"
    INSTALL_RPATH "@executable_path/../Frameworks")
  # Data (effects, locale) lives in Contents/Resources of the bundle.
  add_custom_command(TARGET obs-layercast POST_BUILD
    COMMAND ${CMAKE_COMMAND} -E copy_directory "${PLUGIN_DATA}" "$<TARGET_BUNDLE_CONTENT_DIR:obs-layercast>/Resources"
    COMMAND codesign --force --sign - "$<TARGET_BUNDLE_DIR:obs-layercast>"
    VERBATIM)
  set(LC_USER_PLUGIN_DIR "$ENV{HOME}/Library/Application Support/obs-studio/plugins")
  install(DIRECTORY "$<TARGET_BUNDLE_DIR:obs-layercast>" DESTINATION "${LC_USER_PLUGIN_DIR}" USE_SOURCE_PERMISSIONS)
elseif(WIN32)
  target_sources(obs-layercast PRIVATE src/lc-texture-windows.c)
  target_compile_definitions(obs-layercast PRIVATE _CRT_SECURE_NO_WARNINGS)
  if(MSVC)
    # Generate an import library from the installed obs.dll.
    set(OBS_IMPORT_LIB "${CMAKE_CURRENT_BINARY_DIR}/obs.lib")
    target_link_libraries(obs-layercast PRIVATE "${OBS_IMPORT_LIB}")
  else()
    # MinGW links directly against the DLL.
    target_link_libraries(obs-layercast PRIVATE "${OBS_DIR}/bin/64bit/obs.dll")
    target_link_options(obs-layercast PRIVATE -static-libgcc)
  endif()
  install(TARGETS obs-layercast LIBRARY DESTINATION "obs-plugins/64bit")
  install(DIRECTORY "${PLUGIN_DATA}/" DESTINATION "data/obs-plugins/obs-layercast")
else()
  target_sources(obs-layercast PRIVATE src/lc-texture-linux.c)
  if(OBS_LIBRARY)
    target_link_libraries(obs-layercast PRIVATE "${OBS_LIBRARY}")
  else()
    find_library(LIBOBS obs REQUIRED)
    target_link_libraries(obs-layercast PRIVATE "${LIBOBS}")
  endif()
  target_link_libraries(obs-layercast PRIVATE rt)
  install(TARGETS obs-layercast LIBRARY DESTINATION "lib/obs-plugins")
  install(DIRECTORY "${PLUGIN_DATA}/" DESTINATION "share/obs/obs-plugins/obs-layercast")
endif()
