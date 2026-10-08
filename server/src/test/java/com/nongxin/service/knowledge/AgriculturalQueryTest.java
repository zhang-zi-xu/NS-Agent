package com.nongxin.service.knowledge;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class AgriculturalQueryTest {
    @Test
    void canonicalVocabularyIsIdempotentAndStagesAreNotSynonyms() {
        String query = "水稻稻瘟病、稻飞虱；小麦赤霉病，破口前和齐穗期";
        assertThat(AgriculturalQuery.normalize(query)).isEqualTo(query);
        assertThat(AgriculturalQuery.normalize(AgriculturalQuery.normalize(query)))
                .isEqualTo(query);
        assertThat(AgriculturalQuery.normalize("叶瘟 穗颈瘟 条锈 叶锈病")).isEqualTo("叶瘟 穗瘟 条锈病 叶锈病");
    }

    @Test
    void colloquialCropNamesDoNotDiagnoseSymptoms() {
        assertThat(AgriculturalQuery.crop("稻子淹了怎么排水")).isEqualTo("水稻");
        assertThat(AgriculturalQuery.crop("麦苗要上肥吗")).isEqualTo("小麦");
        assertThat(AgriculturalQuery.crop("稻子和麦子的差别")).isNull();
        assertThat(AgriculturalQuery.normalize("叶子发黄 白穗 萎蔫")).isEqualTo("叶子发黄 白穗 萎蔫");
        assertThat(AgriculturalQuery.normalize("钻心虫 高温逼熟")).isEqualTo("钻心虫 高温逼熟");
        assertThat(AgriculturalQuery.terms("水稻防治管理怎么办")).doesNotContain("水稻", "防治", "管理", "怎么办");
    }
}
