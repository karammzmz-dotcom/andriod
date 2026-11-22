cmake_minimum_required(VERSION 3.22.1)
project(catacombs)
add_library(catacombs SHARED native-lib.cpp
    game/Draw.cpp
    game/Enemy.cpp
    game/Entity.cpp
    game/FixedMath.cpp
    game/Font.cpp
    game/Game.cpp
    game/Map.cpp
    game/MapGenerator.cpp
    game/Menu.cpp
    game/Particle.cpp
    game/Player.cpp
    game/Projectile.cpp
    game/Sounds.cpp
 )
target_include_directories(catacombs PRIVATE game)
target_compile_options(catacombs PRIVATE -O2 -fno-exceptions -fno-rtti)
target_link_libraries(catacombs PRIVATE dl log)
