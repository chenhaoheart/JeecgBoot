<template>
  <PageWrapper dense content-full-height fixed-height>
    <AiAppSessionChat
      app-id="1996471445272088578"
      session-type="imgrec"
      name="图像识别"
      icon="https://jeecgdev.oss-cn-beijing.aliyuncs.com/temp/1dataOCR_1743065089791.png"
      prologue="上传一张图片，我来为你识别图片的内容"
    />
  </PageWrapper>
</template>

<script lang="ts" setup>
  // 图像识别菜单页：只绑定应用和会话类型，会话布局在 AiAppSessionChat。
  import { PageWrapper } from '/@/components/Page';
  import AiAppSessionChat from '/@/views/super/airag/aiapp/components/AiAppSessionChat.vue';

  defineOptions({ name: 'super-airag-imgrec-ImageRecognition' });
</script>
