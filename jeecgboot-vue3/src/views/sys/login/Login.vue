<template>
  <div :class="prefixCls" class="relative w-full h-full">
    <!-- 顶部工具条 -->
    <div class="absolute top-0 left-0 right-0 z-20 flex items-center justify-between px-6 py-4">
      <AppLogo class="!flex items-center" alwaysShowTitle />
      <div class="flex items-center gap-3">
        <AppLocalePicker v-if="!sessionTimeout && showLocale" :showText="false" />
        <AppDarkModeToggle v-if="!sessionTimeout" />
      </div>
    </div>

    <!-- 背景装饰 -->
    <div class="login-deco login-deco--1"></div>
    <div class="login-deco login-deco--2"></div>
    <div class="login-deco login-deco--3"></div>

    <div class="relative z-10 h-full flex items-center justify-center px-4 sm:px-10">
      <div class="login-panel flex w-full max-w-5xl rounded-2xl overflow-hidden">
        <!-- 左侧品牌区 -->
        <div class="hidden lg:flex lg:w-1/2 flex-col justify-between p-10 login-brand">
          <div>
            <h1 class="text-4xl font-bold text-white leading-tight mt-16">{{ t('sys.login.signInTitle') }}</h1>
            <p class="mt-4 text-base text-white/70">{{ t('sys.login.signInDesc') }}</p>
          </div>
          <ul class="space-y-4 mb-8">
            <li v-for="i in 3" :key="i" class="flex items-center gap-3 text-white/85 text-sm">
              <span class="login-check">✓</span>
              {{ t(`sys.login.feature${i}`) }}
            </li>
          </ul>
        </div>

        <!-- 右侧表单区 -->
        <div class="w-full lg:w-1/2 flex items-center justify-center bg-white dark:bg-transparent p-6 sm:p-10">
          <div class="w-full max-w-sm">
            <div class="lg:hidden mb-8 text-center">
              <AppLogo :alwaysShowTitle="true" />
            </div>
            <LoginForm />
            <ForgetPasswordForm />
            <RegisterForm />
            <MobileForm />
            <QrCodeForm />
          </div>
        </div>
      </div>
    </div>
  </div>
</template>
<script lang="ts" setup>
  import { computed } from 'vue';
  import { AppLogo } from '/@/components/Application';
  import { AppLocalePicker, AppDarkModeToggle } from '/@/components/Application';
  import LoginForm from './LoginForm.vue';
  import ForgetPasswordForm from './ForgetPasswordForm.vue';
  import RegisterForm from './RegisterForm.vue';
  import MobileForm from './MobileForm.vue';
  import QrCodeForm from './QrCodeForm.vue';
  import { useGlobSetting } from '/@/hooks/setting';
  import { useI18n } from '/@/hooks/web/useI18n';
  import { useDesign } from '/@/hooks/web/useDesign';
  import { useLocaleStore } from '/@/store/modules/locale';
  import { useLoginState } from './useLogin';
  defineProps({
    sessionTimeout: {
      type: Boolean,
    },
  });

  const globSetting = useGlobSetting();
  const { prefixCls } = useDesign('login');
  const { t } = useI18n();
  const localeStore = useLocaleStore();
  const showLocale = localeStore.getShowPicker;
  const title = computed(() => globSetting?.title ?? '');
  const { handleBackLogin } = useLoginState();
  handleBackLogin();
</script>
<style lang="less">
  @prefix-cls: ~'@{namespace}-login';
  @logo-prefix-cls: ~'@{namespace}-app-logo';
  @countdown-prefix-cls: ~'@{namespace}-countdown-input';
  @dark-card-bg: rgba(35, 42, 59, 0.85);

  html[data-theme='dark'] {
    .@{prefix-cls} {
      background: linear-gradient(135deg, #1a1f35 0%, #232a3b 50%, #1e2537 100%);

      .login-panel {
        background: @dark-card-bg;
        backdrop-filter: blur(20px);
        box-shadow: 0 20px 60px rgba(0, 0, 0, 0.5);
        border: 1px solid rgba(255, 255, 255, 0.08);
      }

      .login-brand {
        background: linear-gradient(160deg, rgba(89, 74, 226, 0.35) 0%, rgba(46, 101, 218, 0.2) 100%);
        border-right: 1px solid rgba(255, 255, 255, 0.08);
      }

      .ant-input,
      .ant-input-password {
        background-color: #1c2333;
      }

      .ant-btn:not(.ant-btn-link):not(.ant-btn-primary) {
        border: 1px solid #4a5569;
      }

      &-form {
        background: transparent !important;
      }

      .app-iconify {
        color: #fff;
      }
    }

    input.fix-auto-fill,
    .fix-auto-fill input {
      -webkit-text-fill-color: #c9d1d9 !important;
      box-shadow: inherit !important;
    }
  }

  .@{prefix-cls} {
    min-height: 100%;
    overflow: hidden;
    background: linear-gradient(135deg, #667eea 0%, #764ba2 55%, #6b5fd0 100%);
    transition: background 0.3s;

    @media (max-width: @screen-lg) {
      background: linear-gradient(135deg, #667eea 0%, #764ba2 100%);
    }

    // 漂浮光斑装饰
    .login-deco {
      position: absolute;
      border-radius: 50%;
      filter: blur(60px);
      opacity: 0.45;
      pointer-events: none;
      animation: login-float 12s ease-in-out infinite;

      &--1 {
        width: 480px;
        height: 480px;
        top: -160px;
        left: -120px;
        background: rgba(255, 255, 255, 0.25);
      }

      &--2 {
        width: 360px;
        height: 360px;
        bottom: -120px;
        right: -80px;
        background: rgba(94, 231, 212, 0.3);
        animation-delay: -4s;
      }

      &--3 {
        width: 260px;
        height: 260px;
        top: 45%;
        left: 55%;
        background: rgba(255, 176, 218, 0.25);
        animation-delay: -8s;
      }
    }

    .login-panel {
      box-shadow: 0 24px 64px rgba(30, 20, 80, 0.35);

      @media (max-width: @screen-lg) {
        box-shadow: 0 16px 48px rgba(30, 20, 80, 0.4);
      }
    }

    .login-brand {
      background: linear-gradient(160deg, rgba(103, 84, 224, 0.6) 0%, rgba(72, 100, 200, 0.35) 100%);
      border-right: 1px solid rgba(255, 255, 255, 0.12);
    }

    .login-check {
      display: inline-flex;
      align-items: center;
      justify-content: center;
      width: 22px;
      height: 22px;
      border-radius: 50%;
      background: rgba(255, 255, 255, 0.18);
      font-size: 12px;
      flex-shrink: 0;
    }

    .@{logo-prefix-cls} {
      &__title {
        font-size: 16px;
        color: #fff;
      }

      img {
        width: 32px;
      }
    }

    &-sign-in-way {
      .anticon {
        font-size: 22px;
        color: #999;
        cursor: pointer;
        transition: color 0.2s, transform 0.2s;

        &:hover {
          color: @primary-color;
          transform: translateY(-2px);
        }
      }
    }

    input:not([type='checkbox']) {
      min-width: 100%;

      @media (max-width: @screen-sm) {
        min-width: 160px;
      }
    }

    .@{countdown-prefix-cls} input {
      min-width: unset !important;
    }

    .ant-divider-inner-text {
      font-size: 12px;
      color: @text-color-secondary;
    }

    .ant-btn {
      border-radius: 6px;
    }

    .ant-input-affix-wrapper,
    .ant-input {
      border-radius: 6px;
    }
  }

  @keyframes login-float {
    0%,
    100% {
      transform: translate(0, 0) scale(1);
    }
    33% {
      transform: translate(30px, -30px) scale(1.06);
    }
    66% {
      transform: translate(-20px, 20px) scale(0.96);
    }
  }
</style>
