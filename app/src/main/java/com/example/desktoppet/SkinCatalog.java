package com.example.desktoppet;

final class SkinCatalog {
    static final class Skin {
        final String name;
        final String description;
        final int resourceId;

        Skin(String name, String description, int resourceId) {
            this.name = name;
            this.description = description;
            this.resourceId = resourceId;
        }
    }

    static final Skin[] BUILT_INS = {
            new Skin("雾术师 · Q版", "二次元 Q 版人物", R.drawable.skin_chibi),
            new Skin("雾团精灵", "软萌云雾团子", R.drawable.skin_mist_blob),
            new Skin("像素雾狐", "复古像素宠物", R.drawable.skin_pixel_fox),
            new Skin("手绘雾猫", "手绘卡通动物", R.drawable.skin_cartoon_cat),
            new Skin("雾境守望者", "Live2D 感人物", R.drawable.skin_live2d),
            new Skin("雾核玩偶", "3D 潮玩角色", R.drawable.skin_3d_toy)
    };

    private SkinCatalog() {}

    static int safeIndex(int index) {
        return index >= 0 && index < BUILT_INS.length ? index : 1;
    }

    static String[] names() {
        String[] names = new String[BUILT_INS.length];
        for (int i = 0; i < BUILT_INS.length; i++) names[i] = BUILT_INS[i].name;
        return names;
    }
}
