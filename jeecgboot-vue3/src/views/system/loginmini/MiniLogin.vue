<template>
  <div :class="prefixCls" class="login-background-img">
    <AppLocalePicker class="absolute top-4 right-4 z-20" :showText="false" />
    <AppDarkModeToggle class="absolute top-3 right-7 z-20" />

    <!-- 全屏星网：中心 AI 智能体 + 光轨 + 功能气泡 -->
    <div class="ai-network" aria-hidden="true">
      <!-- 光轨连线层 -->
      <svg class="ai-links" viewBox="0 0 100 100" preserveAspectRatio="none">
        <g v-for="(b, i) in bubbles" :key="'link' + i" :class="{ 'is-hidden-mobile': b.hideOnMobile }">
          <line
            class="ai-link-line"
            :x1="HUB.x"
            :y1="HUB.y"
            :x2="b.x"
            :y2="b.y"
            vector-effect="non-scaling-stroke"
          />
          <circle class="ai-link-dot" r="0.6" vector-effect="non-scaling-stroke">
            <animateMotion
              :dur="3.2 + (i % 3) * 0.7 + 's'"
              :begin="-(i * 0.55) + 's'"
              repeatCount="indefinite"
              :path="`M${HUB.x},${HUB.y} L${b.x},${b.y}`"
            />
          </circle>
        </g>
      </svg>

      <!-- 功能气泡 -->
      <div
        v-for="b in bubbles"
        :key="b.label"
        class="ai-bubble"
        :class="[`ai-bubble--${b.size}`, { 'is-hidden-mobile': b.hideOnMobile }]"
        :style="{ top: b.y + '%', left: b.x + '%', animationDelay: b.delay, animationDuration: b.duration }"
      >
        <span class="ai-bubble-glow"></span>
        <span class="ai-bubble-icon"><component :is="b.icon" /></span>
        <span class="ai-bubble-label">{{ b.label }}</span>
      </div>

      <!-- 中心大节点：AI 智能体 -->
      <div class="ai-hub" :style="{ left: HUB.x + '%', top: HUB.y + '%' }">
        <span class="ai-hub-ring ai-hub-ring--1"></span>
        <span class="ai-hub-ring ai-hub-ring--2"></span>
        <div class="ai-hub-core">
          <span class="ai-hub-icon"><RobotOutlined /></span>
          <span class="ai-hub-name">AI 智能体</span>
          <span class="ai-hub-sub">AGENT HUB</span>
        </div>
      </div>
    </div>

    <div v-show="type === 'login'" class="ai-stage">
      <section class="ai-card">
        <header class="ai-card-header">
          <div class="ai-card-badge anim-fade-up">
            <span class="ai-card-dot"></span>AI AGENT PLATFORM
          </div>
          <h1 class="ai-card-title anim-fade-up" style="animation-delay: 0.08s">AI 智能体应用</h1>
          <p class="ai-card-subtitle anim-fade-up" style="animation-delay: 0.16s">登录以构建和管理你的专属智能体</p>
        </header>

        <div class="ai-tabs anim-fade-up" style="animation-delay: 0.24s">
          <span class="ai-tabs-thumb" :class="{ 'is-phone': activeIndex === 'phoneLogin' }"></span>
          <div class="ai-tabs-item" :class="{ active: activeIndex === 'accountLogin' }" @click="loginClick('accountLogin')">
            {{ t('sys.login.signInFormTitle') }}
          </div>
          <div class="ai-tabs-item" :class="{ active: activeIndex === 'phoneLogin' }" @click="loginClick('phoneLogin')">
            {{ t('sys.login.mobileSignInFormTitle') }}
          </div>
        </div>

        <a-form v-if="activeIndex === 'accountLogin'" class="ai-form anim-fade-up" style="animation-delay: 0.32s" @keyup.enter.native="loginHandleClick">
          <div class="ai-field">
            <UserOutlined class="ai-field-icon" />
            <a-input class="fix-auto-fill ai-input" :placeholder="t('sys.login.userName')" v-model:value="formData.username" />
          </div>
          <div class="ai-field">
            <LockOutlined class="ai-field-icon" />
            <a-input class="fix-auto-fill ai-input" type="password" :placeholder="t('sys.login.password')" v-model:value="formData.password" />
          </div>
          <div class="ai-field" v-if="showDepart">
            <ApartmentOutlined class="ai-field-icon" />
            <a-select
              class="ai-select"
              allow-clear
              v-model:value="formData.loginOrgCode"
              :placeholder="t('sys.login.loginOrgCode')"
              :options="departList"
            />
          </div>
          <div class="ai-form-row">
            <a-checkbox v-model:checked="rememberMe">{{ t('sys.login.rememberMe') }}</a-checkbox>
            <a class="ai-link" @click="forgetHandelClick">{{ t('sys.login.forgetPassword') }}</a>
          </div>
        </a-form>

        <a-form v-else class="ai-form anim-fade-up" style="animation-delay: 0.32s" @keyup.enter.native="loginHandleClick">
          <div class="ai-field">
            <MobileOutlined class="ai-field-icon" />
            <a-input class="fix-auto-fill ai-input" :placeholder="t('sys.login.mobile')" v-model:value="phoneFormData.mobile" />
          </div>
          <div class="ai-field">
            <SafetyOutlined class="ai-field-icon" />
            <a-input class="fix-auto-fill ai-input ai-input--sms" :maxlength="6" :placeholder="t('sys.login.smsCode')" v-model:value="phoneFormData.smscode" />
            <button type="button" class="ai-sms-btn" :disabled="!showInterval" @click="getLoginCode">
              {{ showInterval ? t('component.countdown.normalText') : t('component.countdown.sendText', [unref(timeRuning)]) }}
            </button>
          </div>
          <div class="ai-field" v-if="showDepart">
            <ApartmentOutlined class="ai-field-icon" />
            <a-select
              class="ai-select"
              allow-clear
              v-model:value="phoneFormData.loginOrgCode"
              :placeholder="t('sys.login.loginOrgCode')"
              :options="departList"
            />
          </div>
        </a-form>

        <a-button :loading="loginLoading" class="ai-submit anim-fade-up" style="animation-delay: 0.4s" type="primary" @click="loginHandleClick">
          {{ t('sys.login.loginButton') }}
        </a-button>

        <div class="ai-quick anim-fade-up" style="animation-delay: 0.48s">
          <a class="ai-link" @click="codeHandleClick">{{ t('sys.login.qrSignInFormTitle') }}</a>
          <span class="ai-quick-divider"></span>
          <a class="ai-link" @click="registerHandleClick">{{ t('sys.login.registerButton') }}</a>
        </div>

        <div class="ai-third anim-fade-up" style="animation-delay: 0.56s">
          <div class="ai-third-title">
            <span class="ai-third-line"></span>
            <span>{{ t('sys.login.otherSignIn') }}</span>
            <span class="ai-third-line"></span>
          </div>
          <div class="ai-third-icons">
            <button type="button" class="ai-third-item" title="github" @click="onThirdLogin('github')"><GithubFilled /></button>
            <button type="button" class="ai-third-item" title="企业微信" @click="onThirdLogin('wechat_enterprise')">
              <icon-font class="item-icon" type="icon-qiyeweixin3" />
            </button>
            <button type="button" class="ai-third-item" title="钉钉" @click="onThirdLogin('dingtalk')"><DingtalkCircleFilled /></button>
            <button type="button" class="ai-third-item" title="微信" @click="onThirdLogin('wechat_open')"><WechatFilled /></button>
          </div>
        </div>
      </section>
    </div>

    <div v-if="forgotLoaded" v-show="type === 'forgot'" :class="`${prefixCls}-form`">
      <MiniForgotpad ref="forgotRef" @go-back="goBack" @success="handleSuccess" />
    </div>
    <div v-if="registerLoaded" v-show="type === 'register'" :class="`${prefixCls}-form`">
      <MiniRegister ref="registerRef" @go-back="goBack" @success="handleSuccess" />
    </div>
    <div v-if="codeLoginLoaded" v-show="type === 'codeLogin'" :class="`${prefixCls}-form`">
      <MiniCodelogin ref="codeRef" @go-back="goBack" @success="handleSuccess" />
    </div>
    <!-- 第三方登录相关弹框 -->
    <ThirdModal ref="thirdModalRef"></ThirdModal>

    <!-- 短信发送图片验证码弹窗 -->
    <CaptchaModal @register="captchaRegisterModal" @ok="getLoginCode" />
  </div>
</template>
<script lang="ts" setup name="login-mini">
  import { getCaptcha } from '/@/api/sys/user';
  import { computed, defineAsyncComponent, onMounted, reactive, ref, toRaw, unref, watch } from 'vue';
  import { useUserStore } from '/@/store/modules/user';
  import { useMessage } from '/@/hooks/web/useMessage';
  import { useI18n } from '/@/hooks/web/useI18n';
  import { SmsEnum } from '/@/views/sys/login/useLogin';
  import ThirdModal from '/@/views/sys/login/ThirdModal.vue';
  const MiniForgotpad = defineAsyncComponent(() => import('./MiniForgotpad.vue'));
  const MiniRegister = defineAsyncComponent(() => import('./MiniRegister.vue'));
  const MiniCodelogin = defineAsyncComponent(() => import('./MiniCodelogin.vue'));
  import { AppLocalePicker, AppDarkModeToggle } from '/@/components/Application';
  import { useLocaleStore } from '/@/store/modules/locale';
  import { createLocalStorage } from '/@/utils/cache';
  import { useDesign } from "/@/hooks/web/useDesign";
  import {
    GithubFilled,
    WechatFilled,
    DingtalkCircleFilled,
    UserOutlined,
    LockOutlined,
    MobileOutlined,
    SafetyOutlined,
    ApartmentOutlined,
    BookOutlined,
    ThunderboltOutlined,
    DeploymentUnitOutlined,
    BarChartOutlined,
    AudioOutlined,
    AppstoreOutlined,
    EyeOutlined,
    RobotOutlined,
  } from '@ant-design/icons-vue';
  import '/@/utils/iconfont2';
  import CaptchaModal from '@/components/jeecg/captcha/CaptchaModal.vue';
  import { useModal } from "@/components/Modal";
  import { ExceptionEnum } from "@/enums/exceptionEnum";
  import { encryptAESCBC } from '/@/utils/cipher';
  import { defHttp } from "@/utils/http/axios";
  import { IconFont } from '/@/utils/iconfont2';

  // 中心节点坐标（百分比），光轨汇聚点
  const HUB = { x: 24, y: 50 };
  // 全屏功能气泡（围绕中心节点散列，避开右侧登录卡区域）
  const bubbles = [
    { label: '多智能体编排', icon: ApartmentOutlined, size: 'lg', x: 20, y: 12, delay: '0s', duration: '9s', hideOnMobile: false },
    { label: '大模型接入', icon: ThunderboltOutlined, size: 'sm', x: 41, y: 17, delay: '-4s', duration: '8.5s', hideOnMobile: true },
    { label: '插件生态', icon: AppstoreOutlined, size: 'sm', x: 45, y: 41, delay: '-6s', duration: '7s', hideOnMobile: true },
    { label: '工作流自动化', icon: DeploymentUnitOutlined, size: 'lg', x: 40, y: 68, delay: '-1s', duration: '10s', hideOnMobile: true },
    { label: '知识库问答', icon: BookOutlined, size: 'md', x: 22, y: 87, delay: '-2s', duration: '7.5s', hideOnMobile: false },
    { label: '多模态理解', icon: EyeOutlined, size: 'md', x: 8, y: 79, delay: '-2.5s', duration: '8.5s', hideOnMobile: true },
    { label: '语音对话', icon: AudioOutlined, size: 'md', x: 5, y: 46, delay: '-5s', duration: '9.5s', hideOnMobile: false },
    { label: '数据分析', icon: BarChartOutlined, size: 'sm', x: 7, y: 18, delay: '-3s', duration: '8s', hideOnMobile: true },
  ];

  const { prefixCls } = useDesign('mini-login');
  const { notification, createMessage } = useMessage();
  const userStore = useUserStore();
  const { t } = useI18n();
  const $ls = createLocalStorage();
  const localeStore = useLocaleStore();
  const showLocale = localeStore.getShowPicker;
  // 记住用户名
  const rememberMe = ref<boolean>(false);
  const REMEMBER_USERNAME_KEY = 'LOGIN_REMEMBER_USERNAME';
  //手机号登录还是账号登录
  const activeIndex = ref<string>('accountLogin');
  const type = ref<string>('login');
  //账号登录表单字段
  const formData = reactive<any>({
    username: 'admin',
    password: '123456',
    loginOrgCode: '',
  });
  //手机登录表单字段
  const phoneFormData = reactive<any>({
    mobile: '',
    smscode: '',
    loginOrgCode: '',
  });
  //第三方登录弹窗
  const thirdModalRef = ref();
  //扫码登录
  const codeRef = ref();
  //是否显示获取验证码
  const showInterval = ref<boolean>(true);
  //60s
  const timeRuning = ref<number>(60);
  //定时器
  const timer = ref<any>(null);
  //忘记密码
  const forgotRef = ref();
  //注册
  const registerRef = ref();
  const loginLoading = ref<boolean>(false);
  // 以下三个组件懒加载控制标志位，点击时才挂载
  const forgotLoaded = ref<boolean>(false);
  const registerLoaded = ref<boolean>(false);
  const codeLoginLoaded = ref<boolean>(false);
  const [captchaRegisterModal, { openModal: openCaptchaModal }] = useModal();
  defineProps({
    sessionTimeout: {
      type: Boolean,
    },
  });
 //**********************查询部门逻辑begin**********************************************
  //用户部门
  const departList = ref([]);
  //部门显示
  const showDepart = computed(()=>{
    return departList.value.length > 1
  })
  //监听表单输入，账号密码（或手机号+短信码）填齐后查询部门信息
  watch(
      () => [formData.username, formData.password, phoneFormData.mobile, phoneFormData.smscode, activeIndex.value],
      () => {
        formData.loginOrgCode = null;
        phoneFormData.loginOrgCode = null;
        departList.value = [];
        checkAccount()
      },
  );

  //初始化数据
  let deptTimer;
  function checkAccount() {
    deptTimer && clearTimeout(deptTimer);
    deptTimer = setTimeout(async () => {
      let loginType = activeIndex.value === 'accountLogin' ? 'account' : 'phone';
      // 验证条件提取
      const isValidAccount = loginType === 'account' && formData.username && formData.password;
      const isValidPhone = loginType == 'phone' && phoneFormData.mobile && phoneFormData.smscode;
      let finalFormData = loginType == 'phone' ? {...phoneFormData} : {...formData};
      if (!isValidAccount && !isValidPhone) {
        return;
      }
      //查询部门信息前，优先进行账户校验
      if (departList.value && departList.value.length == 0) {
        let params = {...finalFormData, loginType: activeIndex.value === 'accountLogin' ? 'account' : 'phone'};
        if (loginType == 'account') {
          params['password'] = encryptAESCBC(formData.password);
        }
        const res = await defHttp.post({
          url: '/sys/loginGetUserDeparts',
          params: {...params}
        }, {isTransformResponse: false});
        if (res.success && res.result) {
          let {departs,currentOrgCode} = res.result;
          // 判断当前部门是否在所属的部门列表中
          if (departs && departs.length > 0) {
            // 代码逻辑说明: JHHB-790 用户部门变更，会出现这个情况（因为之前设置的这里只切换部门，过滤了公司和岗位信息）
            const hasCurrentDepart = departs.some(item => item.orgCode == currentOrgCode);
            formData.loginOrgCode = hasCurrentDepart?currentOrgCode:null;
            phoneFormData.loginOrgCode = hasCurrentDepart?currentOrgCode:null;
            departList.value = departs.map((item) => {
              return {
                label: item.departName,
                value: item.orgCode,
                orgCode: item.orgCode,
                departName: item.departName,
              };
            });
          }
        } else {
          //createMessage.warn(res.message);
        }
      }
    },500)
  }
 //**********************查询部门逻辑end*************************************************
  /**
   * 切换登录方式
   */
  function loginClick(type) {
    activeIndex.value = type;
  }

  /**
   * 账号或者手机登录
   */
  async function loginHandleClick() {
    if (unref(activeIndex) === 'accountLogin') {
      accountLogin();
    } else {
      //手机号登录
      phoneLogin();
    }
  }

  async function accountLogin() {
    if (!formData.username) {
      createMessage.warn(t('sys.login.accountPlaceholder'));
      return;
    }
    if (!formData.password) {
      createMessage.warn(t('sys.login.passwordPlaceholder'));
      return;
    }
    try {
      loginLoading.value = true;

      // 密码使用AES加密传输
      const encryptedPassword = encryptAESCBC(formData.password);
      const { userInfo } = await userStore.login(
        toRaw({
          password: encryptedPassword,
          username: formData.username,
          loginOrgCode: formData.loginOrgCode,
          mode: 'none', //不要默认的错误提示
        })
      );
      if (userInfo) {
        notification.success({
          message: t('sys.login.loginSuccessTitle'),
          description: `${t('sys.login.loginSuccessDesc')}: ${userInfo.realname}`,
          duration: 3,
        });
        // 登录成功后处理记住用户名
        if (rememberMe.value && formData.username) {
          $ls.set(REMEMBER_USERNAME_KEY, formData.username)
        } else {
          $ls.remove(REMEMBER_USERNAME_KEY)
        }
      }
    } catch (error) {
      notification.error({
        message: t('sys.api.errorTip'),
        description: error.message || t('sys.login.networkExceptionMsg'),
        duration: 3,
      });
    } finally {
      loginLoading.value = false;
    }
  }

  /**
   * 手机号登录
   */
  async function phoneLogin() {
    if (!phoneFormData.mobile) {
      createMessage.warn(t('sys.login.mobilePlaceholder'));
      return;
    }
    if (!phoneFormData.smscode) {
      createMessage.warn(t('sys.login.smsPlaceholder'));
      return;
    }
    try {
      loginLoading.value = true;
      const { userInfo }: any = await userStore.phoneLogin({
        mobile: phoneFormData.mobile,
        captcha: phoneFormData.smscode,
        loginOrgCode: phoneFormData.loginOrgCode,
        mode: 'none', //不要默认的错误提示
      });
      if (userInfo) {
        notification.success({
          message: t('sys.login.loginSuccessTitle'),
          description: `${t('sys.login.loginSuccessDesc')}: ${userInfo.realname}`,
          duration: 3,
        });
      }
    } catch (error) {
      notification.error({
        message: t('sys.api.errorTip'),
        description: error.message || t('sys.login.networkExceptionMsg'),
        duration: 3,
      });
    } finally {
      loginLoading.value = false;
    }
  }

  /**
   * 获取手机验证码
   */
  async function getLoginCode() {
    if (!phoneFormData.mobile) {
      createMessage.warn(t('sys.login.mobilePlaceholder'));
      return;
    }
    // 代码逻辑说明: 【issues/8567】严重：修改密码存在水平越权问题：登录应该用登录模板不应该用忘记密码的模板---
    const result = await getCaptcha({ mobile: phoneFormData.mobile, smsmode: SmsEnum.LOGIN }).catch((res) =>{
      if(res.code === ExceptionEnum.PHONE_SMS_FAIL_CODE){
        openCaptchaModal(true, {});
      }
    });
    if (result) {
      const TIME_COUNT = 60;
      if (!unref(timer)) {
        timeRuning.value = TIME_COUNT;
        showInterval.value = false;
        timer.value = setInterval(() => {
          if (unref(timeRuning) > 0 && unref(timeRuning) <= TIME_COUNT) {
            timeRuning.value = timeRuning.value - 1;
          } else {
            showInterval.value = true;
            clearInterval(unref(timer));
            timer.value = null;
          }
        }, 1000);
      }
    }
  }

  /**
   * 第三方登录
   * @param type
   */
  function onThirdLogin(type) {
    thirdModalRef.value.onThirdLogin(type);
  }

  /**
   * 忘记密码
   */
  function forgetHandelClick() {
    forgotLoaded.value = true;
    type.value = 'forgot';
    setTimeout(() => {
      forgotRef.value?.initForm();
    }, 300);
  }

  /**
   * 返回登录页面
   */
  function goBack() {
    activeIndex.value = 'accountLogin';
    type.value = 'login';
  }

  /**
   * 忘记密码/注册账号回调事件
   * @param value
   */
  function handleSuccess(value) {
    Object.assign(formData, value);
    Object.assign(phoneFormData, { mobile: "", smscode: "" });
    type.value = 'login';
    activeIndex.value = 'accountLogin';
  }

  /**
   * 注册
   */
  function registerHandleClick() {
    registerLoaded.value = true;
    type.value = 'register';
    setTimeout(() => {
      registerRef.value?.initForm();
    }, 300);
  }

  /**
   * 注册
   */
  function codeHandleClick() {
    codeLoginLoaded.value = true;
    type.value = 'codeLogin';
    setTimeout(() => {
      codeRef.value?.initFrom();
    }, 300);
  }

  onMounted(() => {
    // 恢复已记住的用户名
    const saved = $ls.get(REMEMBER_USERNAME_KEY);
    if (saved) {
      formData.username = saved;
      rememberMe.value = true;
    }
  });
</script>

<style lang="less" scoped>
  /* ================= 主题变量（浅色为默认值，深色在全局样式块中覆盖） ================= */
  .login-background-img {
    /* 页面背景 */
    --ai-glow-a: rgba(99, 102, 241, 0.16);
    --ai-glow-b: rgba(168, 85, 247, 0.14);
    --ai-page-base: linear-gradient(135deg, #eef2ff 0%, #f3f0ff 48%, #faf5ff 100%);
    /* 玻璃卡片 */
    --ai-card-bg: linear-gradient(165deg, rgba(255, 255, 255, 0.82), rgba(255, 255, 255, 0.62));
    --ai-card-border: rgba(255, 255, 255, 0.9);
    --ai-card-shadow: 0 30px 80px rgba(79, 70, 160, 0.18), inset 0 1px 0 rgba(255, 255, 255, 0.9);
    /* 文字 */
    --ai-text: #1e2345;
    --ai-text-sub: #5b6184;
    --ai-text-faint: #9aa0c0;
    /* 标题渐变 */
    --ai-title-gradient: linear-gradient(90deg, #1d4ed8 0%, #6d28d9 50%, #1d4ed8 100%);
    /* 徽标 */
    --ai-badge-bg: rgba(255, 255, 255, 0.7);
    --ai-badge-border: rgba(99, 102, 241, 0.25);
    --ai-badge-text: #4f46e5;
    /* 气泡 */
    --ai-bubble-bg: linear-gradient(150deg, rgba(255, 255, 255, 0.95), rgba(255, 255, 255, 0.55));
    --ai-bubble-border: rgba(255, 255, 255, 0.95);
    --ai-bubble-shadow: 0 12px 40px rgba(99, 102, 241, 0.16), inset 0 1px 0 rgba(255, 255, 255, 0.95);
    --ai-bubble-text: #5b5591;
    --ai-bubble-icon-gradient: linear-gradient(135deg, #6366f1, #a855f7);
    --ai-bubble-hover-border: rgba(99, 102, 241, 0.45);
    --ai-bubble-ring: rgba(99, 102, 241, 0.45);
    /* 光轨连线 */
    --ai-link-stroke: rgba(99, 102, 241, 0.28);
    --ai-link-dot: #6366f1;
    /* 中心节点 */
    --ai-hub-core-bg: linear-gradient(155deg, rgba(255, 255, 255, 0.95), rgba(237, 233, 254, 0.85));
    --ai-hub-core-border: rgba(255, 255, 255, 0.95);
    --ai-hub-name: #4338ca;
    --ai-hub-sub: #818cf8;
    --ai-hub-icon-gradient: linear-gradient(135deg, #4f46e5, #9333ea);
    --ai-hub-ring: rgba(99, 102, 241, 0.5);
    /* Tabs */
    --ai-tabs-bg: rgba(99, 102, 241, 0.08);
    --ai-tabs-border: rgba(99, 102, 241, 0.12);
    --ai-tabs-inactive: #8a8fb5;
    /* 输入框 */
    --ai-field-bg: rgba(255, 255, 255, 0.65);
    --ai-field-border: rgba(99, 102, 241, 0.16);
    --ai-field-focus-bg: rgba(255, 255, 255, 0.9);
    --ai-field-focus-border: rgba(99, 102, 241, 0.65);
    --ai-field-focus-ring: rgba(99, 102, 241, 0.14);
    --ai-field-icon: #a2a8cc;
    --ai-field-icon-focus: #6366f1;
    --ai-input-text: #1e2345;
    --ai-input-placeholder: #aab0ce;
    /* 链接/复选 */
    --ai-link: #4f46e5;
    --ai-link-hover: #6366f1;
    --ai-checkbox-label: #5b6184;
    --ai-checkbox-box-bg: rgba(255, 255, 255, 0.7);
    --ai-checkbox-box-border: rgba(99, 102, 241, 0.35);
    /* 短信按钮 */
    --ai-sms-border: rgba(99, 102, 241, 0.5);
    --ai-sms-bg: rgba(99, 102, 241, 0.1);
    --ai-sms-text: #4f46e5;
    --ai-sms-disabled-border: rgba(154, 160, 192, 0.4);
    --ai-sms-disabled-text: #aab0ce;
    /* 第三方登录 */
    --ai-third-title: #9aa0c0;
    --ai-third-line: rgba(99, 102, 241, 0.16);
    --ai-third-item-bg: rgba(255, 255, 255, 0.6);
    --ai-third-item-border: rgba(99, 102, 241, 0.15);
    --ai-third-item-icon: #8a8fb5;
    /* 滑块 */
    --sv-track-bg: rgba(255, 255, 255, 0.55);
    --sv-track-border: rgba(99, 102, 241, 0.18);
    --sv-track-hover-border: rgba(99, 102, 241, 0.5);
    --sv-tip: #9aa0c0;
    --sv-tip-icon: #818cf8;
    --sv-progress: linear-gradient(90deg, rgba(99, 102, 241, 0.16), rgba(168, 85, 247, 0.2));
    --sv-success-bg: rgba(16, 185, 129, 0.1);
    --sv-success-border: rgba(16, 185, 129, 0.4);
    --sv-success-text: #059669;
    --sv-autofill: #eef0ff;
  }

  /* ================= 页面背景 ================= */
  .login-background-img {
    background:
      radial-gradient(1200px 800px at 88% -12%, var(--ai-glow-a), transparent 60%),
      radial-gradient(1000px 720px at -12% 112%, var(--ai-glow-b), transparent 55%),
      var(--ai-page-base) !important;
    background-size: cover;
    min-height: 100vh;
    overflow: hidden;
    transition: background 0.4s ease;
  }

  /* ================= 全屏星网（光轨 + 气泡 + 中心节点） ================= */
  .ai-network {
    position: fixed;
    inset: 0;
    z-index: 1;
    pointer-events: none;
  }
  .ai-links {
    position: absolute;
    inset: 0;
    width: 100%;
    height: 100%;
    overflow: visible;
  }
  .ai-link-line {
    stroke: var(--ai-link-stroke);
    stroke-width: 1.2;
    stroke-dasharray: 3 5;
    stroke-linecap: round;
    animation: link-dash 1.2s linear infinite;
  }
  .ai-link-dot {
    fill: var(--ai-link-dot);
    filter: drop-shadow(0 0 3px var(--ai-link-dot));
  }

  .ai-bubble {
    position: absolute;
    z-index: 2;
    display: flex;
    flex-direction: column;
    align-items: center;
    justify-content: center;
    gap: 7px;
    border-radius: 50%;
    color: var(--ai-bubble-text);
    background: var(--ai-bubble-bg);
    border: 1px solid var(--ai-bubble-border);
    box-shadow: var(--ai-bubble-shadow);
    backdrop-filter: blur(8px);
    transform: translate(-50%, -50%);
    animation-name: bubble-float;
    animation-timing-function: ease-in-out;
    animation-iteration-count: infinite;
    pointer-events: auto;
    transition: border-color 0.3s ease, box-shadow 0.3s ease;

    &:hover {
      border-color: var(--ai-bubble-hover-border);
      box-shadow: 0 18px 50px rgba(99, 102, 241, 0.28), inset 0 1px 0 rgba(255, 255, 255, 0.8);
    }
    &:hover .ai-bubble-icon {
      transform: scale(1.18);
    }

    &--lg {
      width: 118px;
      height: 118px;
    }
    &--md {
      width: 94px;
      height: 94px;
    }
    &--sm {
      width: 74px;
      height: 74px;
    }
  }
  .ai-bubble-icon {
    font-size: 24px;
    background: var(--ai-bubble-icon-gradient);
    -webkit-background-clip: text;
    background-clip: text;
    -webkit-text-fill-color: transparent;
    transition: transform 0.3s ease;
  }
  .ai-bubble--lg .ai-bubble-icon {
    font-size: 30px;
  }
  .ai-bubble--sm .ai-bubble-icon {
    font-size: 20px;
  }
  .ai-bubble-label {
    font-size: 12px;
    letter-spacing: 1px;
    color: var(--ai-bubble-text);
    white-space: nowrap;
  }
  .ai-bubble--sm .ai-bubble-label {
    font-size: 11px;
  }
  .ai-bubble-glow {
    position: absolute;
    inset: -1px;
    border-radius: 50%;
    border: 1px solid var(--ai-bubble-ring);
    opacity: 0;
    pointer-events: none;
  }
  .ai-bubble:nth-of-type(odd) .ai-bubble-glow {
    animation: glow-pulse 3.4s ease-out infinite;
  }
  .ai-bubble:nth-of-type(even) .ai-bubble-glow {
    animation: glow-pulse 3.4s ease-out infinite 1.7s;
  }

  /* ================= 中心大节点：AI 智能体 ================= */
  .ai-hub {
    position: absolute;
    z-index: 3;
    width: 168px;
    height: 168px;
    transform: translate(-50%, -50%);
    animation: hub-float 7s ease-in-out infinite;
  }
  .ai-hub-ring {
    position: absolute;
    inset: 0;
    border-radius: 50%;
    border: 1.5px solid var(--ai-hub-ring);
    pointer-events: none;

    &--1 {
      animation: hub-ring-expand 3.6s ease-out infinite;
    }
    &--2 {
      animation: hub-ring-expand 3.6s ease-out infinite 1.8s;
    }
  }
  .ai-hub-core {
    position: absolute;
    inset: 10px;
    border-radius: 50%;
    display: flex;
    flex-direction: column;
    align-items: center;
    justify-content: center;
    gap: 4px;
    background: var(--ai-hub-core-bg);
    border: 1.5px solid var(--ai-hub-core-border);
    box-shadow: 0 18px 50px rgba(79, 70, 160, 0.35), inset 0 2px 0 rgba(255, 255, 255, 0.6);
    backdrop-filter: blur(12px);
  }
  .ai-hub-icon {
    display: flex;
    align-items: center;
    justify-content: center;
    width: 54px;
    height: 54px;
    border-radius: 50%;
    font-size: 28px;
    margin-bottom: 2px;
    background: var(--ai-hub-icon-gradient);
    -webkit-background-clip: text;
    background-clip: text;
    -webkit-text-fill-color: transparent;
    filter: drop-shadow(0 4px 10px rgba(99, 102, 241, 0.4));
  }
  .ai-hub-name {
    font-size: 16px;
    font-weight: 700;
    letter-spacing: 1.5px;
    color: var(--ai-hub-name);
  }
  .ai-hub-sub {
    font-size: 9px;
    font-weight: 600;
    letter-spacing: 2.5px;
    color: var(--ai-hub-sub);
  }

  /* ================= 舞台与玻璃卡片 ================= */
  .ai-stage {
    position: relative;
    z-index: 2;
    min-height: 100vh;
    display: flex;
    align-items: center;
    justify-content: flex-end;
    padding: 40px 7vw;
  }
  .ai-card {
    width: 420px;
    padding: 36px 34px 28px;
    border-radius: 24px;
    background: var(--ai-card-bg);
    border: 1px solid var(--ai-card-border);
    backdrop-filter: blur(26px) saturate(1.25);
    box-shadow: var(--ai-card-shadow);
    animation: card-in 0.7s cubic-bezier(0.22, 1, 0.36, 1) both;
  }
  .ai-card-badge {
    display: inline-flex;
    align-items: center;
    gap: 8px;
    padding: 5px 14px;
    border: 1px solid var(--ai-badge-border);
    border-radius: 100px;
    background: var(--ai-badge-bg);
    color: var(--ai-badge-text);
    font-size: 11px;
    font-weight: 600;
    letter-spacing: 2px;
  }
  .ai-card-dot {
    width: 7px;
    height: 7px;
    border-radius: 50%;
    background: #22c55e;
    animation: dot-breathe 2.2s ease-out infinite;
  }
  .ai-card-title {
    margin: 14px 0 0;
    font-size: 27px;
    font-weight: 700;
    letter-spacing: 1px;
    line-height: 1.35;
    background: var(--ai-title-gradient);
    background-size: 220% 100%;
    -webkit-background-clip: text;
    background-clip: text;
    -webkit-text-fill-color: transparent;
    animation: title-shine 6s ease-in-out infinite;
  }
  .ai-card-subtitle {
    margin: 8px 0 22px;
    font-size: 13px;
    color: var(--ai-text-sub);
  }

  /* ================= 胶囊 Tabs ================= */
  .ai-tabs {
    position: relative;
    display: flex;
    padding: 4px;
    border-radius: 12px;
    background: var(--ai-tabs-bg);
    border: 1px solid var(--ai-tabs-border);
  }
  .ai-tabs-thumb {
    position: absolute;
    top: 4px;
    left: 4px;
    width: calc(50% - 4px);
    height: calc(100% - 8px);
    border-radius: 9px;
    background: linear-gradient(135deg, #6366f1, #8b5cf6);
    box-shadow: 0 4px 14px rgba(99, 102, 241, 0.35);
    transition: transform 0.35s cubic-bezier(0.22, 1, 0.36, 1);
  }
  .ai-tabs-thumb.is-phone {
    transform: translateX(100%);
  }
  .ai-tabs-item {
    position: relative;
    z-index: 1;
    flex: 1;
    padding: 9px 0;
    text-align: center;
    font-size: 14px;
    color: var(--ai-tabs-inactive);
    cursor: pointer;
    user-select: none;
    transition: color 0.25s ease;
  }
  .ai-tabs-item.active {
    color: #fff;
    font-weight: 600;
  }

  /* ================= 表单 ================= */
  .ai-form {
    margin-top: 6px;
  }
  .ai-field {
    position: relative;
    display: flex;
    align-items: center;
    height: 50px;
    margin-top: 14px;
    border-radius: 12px;
    background: var(--ai-field-bg);
    border: 1px solid var(--ai-field-border);
    transition: border-color 0.25s ease, box-shadow 0.25s ease, background 0.25s ease;
  }
  .ai-field:focus-within {
    border-color: var(--ai-field-focus-border);
    background: var(--ai-field-focus-bg);
    box-shadow: 0 0 0 3px var(--ai-field-focus-ring);
  }
  .ai-field-icon {
    margin-left: 16px;
    font-size: 16px;
    color: var(--ai-field-icon);
    transition: color 0.25s ease;
  }
  .ai-field:focus-within .ai-field-icon {
    color: var(--ai-field-icon-focus);
  }
  .ai-input {
    flex: 1;
    background: transparent !important;
  }
  :deep(.ai-input.ant-input) {
    height: 48px;
    padding: 0 14px 0 10px;
    background: transparent !important;
    border: none !important;
    color: var(--ai-input-text);
    box-shadow: none !important;
  }
  :deep(.ai-input.ant-input::placeholder) {
    color: var(--ai-input-placeholder);
  }
  .ai-input--sms {
    padding-right: 112px !important;
  }
  .ai-select {
    flex: 1;
  }
  :deep(.ai-select .ant-select-selector) {
    height: 48px !important;
    padding: 0 14px 0 10px !important;
    background: transparent !important;
    border: none !important;
    box-shadow: none !important;
  }
  :deep(.ai-select .ant-select-selection-item),
  :deep(.ai-select .ant-select-selection-placeholder) {
    line-height: 46px !important;
    color: var(--ai-input-text);
  }
  :deep(.ai-select .ant-select-selection-placeholder) {
    color: var(--ai-input-placeholder);
  }
  :deep(.ai-select .ant-select-arrow) {
    color: var(--ai-field-icon);
  }
  .ai-sms-btn {
    position: absolute;
    top: 8px;
    right: 8px;
    height: 34px;
    padding: 0 13px;
    border-radius: 9px;
    border: 1px solid var(--ai-sms-border);
    background: var(--ai-sms-bg);
    color: var(--ai-sms-text);
    font-size: 12px;
    white-space: nowrap;
    cursor: pointer;
    transition: all 0.2s ease;
  }
  .ai-sms-btn:hover:not(:disabled) {
    background: rgba(99, 102, 241, 0.2);
  }
  .ai-sms-btn:disabled {
    border-color: var(--ai-sms-disabled-border);
    background: transparent;
    color: var(--ai-sms-disabled-text);
    cursor: not-allowed;
  }
  .ai-form-row {
    display: flex;
    align-items: center;
    justify-content: space-between;
    margin-top: 14px;
    font-size: 13px;
  }
  :deep(.ai-form-row .ant-checkbox-wrapper) {
    color: var(--ai-checkbox-label);
  }
  :deep(.ai-form-row .ant-checkbox-inner) {
    background: var(--ai-checkbox-box-bg);
    border-color: var(--ai-checkbox-box-border);
    border-radius: 5px;
  }
  :deep(.ai-form-row .ant-checkbox-checked .ant-checkbox-inner) {
    background: #6366f1;
    border-color: #6366f1;
  }
  .ai-link {
    font-size: 13px;
    color: var(--ai-link);
    cursor: pointer;
    transition: color 0.2s ease;
  }
  .ai-link:hover {
    color: var(--ai-link-hover);
  }

  /* ================= 提交按钮 ================= */
  .ai-submit {
    position: relative;
    width: 100%;
    height: 50px;
    margin-top: 22px;
    border: none;
    border-radius: 12px;
    background: linear-gradient(135deg, #3b82f6 0%, #6366f1 50%, #a855f7 100%);
    background-size: 200% 100%;
    color: #fff;
    font-size: 15px;
    font-weight: 600;
    letter-spacing: 6px;
    text-indent: 6px;
    box-shadow: 0 10px 30px rgba(99, 102, 241, 0.35);
    overflow: hidden;
    cursor: pointer;
    transition: background-position 0.5s ease, box-shadow 0.25s ease, transform 0.15s ease;
  }
  .ai-submit:hover,
  .ai-submit:focus {
    background-position: 100% 0;
    box-shadow: 0 14px 38px rgba(99, 102, 241, 0.45);
    color: #fff;
  }
  .ai-submit:active {
    transform: translateY(1px) scale(0.995);
  }
  .ai-submit::after {
    content: '';
    position: absolute;
    top: 0;
    left: -60%;
    width: 40%;
    height: 100%;
    background: linear-gradient(100deg, transparent, rgba(255, 255, 255, 0.35), transparent);
    transform: skewX(-20deg);
  }
  .ai-submit:hover::after {
    animation: btn-shine 0.8s ease;
  }
  :deep(.ai-submit.ant-btn-loading) {
    opacity: 0.85;
  }

  /* ================= 快捷入口与第三方 ================= */
  .ai-quick {
    display: flex;
    align-items: center;
    justify-content: center;
    gap: 14px;
    margin-top: 16px;
  }
  .ai-quick-divider {
    width: 1px;
    height: 12px;
    background: var(--ai-third-line);
  }
  .ai-third {
    margin-top: 22px;
  }
  .ai-third-title {
    display: flex;
    align-items: center;
    gap: 12px;
    font-size: 12px;
    color: var(--ai-third-title);
  }
  .ai-third-line {
    flex: 1;
    height: 1px;
    background: linear-gradient(90deg, transparent, var(--ai-third-line));
  }
  .ai-third-line:last-child {
    background: linear-gradient(90deg, var(--ai-third-line), transparent);
  }
  .ai-third-icons {
    display: flex;
    justify-content: center;
    gap: 18px;
    margin-top: 15px;
  }
  .ai-third-item {
    width: 40px;
    height: 40px;
    display: flex;
    align-items: center;
    justify-content: center;
    border-radius: 50%;
    background: var(--ai-third-item-bg);
    border: 1px solid var(--ai-third-item-border);
    color: var(--ai-third-item-icon);
    font-size: 17px;
    cursor: pointer;
    transition: all 0.25s ease;
  }
  .ai-third-item:hover {
    color: #fff;
    border-color: transparent;
    background: linear-gradient(135deg, #3b82f6, #a855f7);
    transform: translateY(-3px);
    box-shadow: 0 8px 20px rgba(99, 102, 241, 0.35);
  }

  /* ================= 入场动画 ================= */
  .anim-fade-up {
    opacity: 0;
    animation: fade-up 0.7s cubic-bezier(0.22, 1, 0.36, 1) forwards;
  }

  @keyframes fade-up {
    from {
      opacity: 0;
      transform: translateY(16px);
    }
    to {
      opacity: 1;
      transform: translateY(0);
    }
  }
  @keyframes card-in {
    from {
      opacity: 0;
      transform: translateY(28px) scale(0.97);
    }
    to {
      opacity: 1;
      transform: translateY(0) scale(1);
    }
  }
  @keyframes bubble-float {
    0%,
    100% {
      transform: translate(-50%, -50%);
    }
    50% {
      transform: translate(-50%, calc(-50% - 12px));
    }
  }
  @keyframes hub-float {
    0%,
    100% {
      transform: translate(-50%, -50%);
    }
    50% {
      transform: translate(-50%, calc(-50% - 10px));
    }
  }
  @keyframes hub-ring-expand {
    0% {
      transform: scale(0.86);
      opacity: 0.9;
    }
    100% {
      transform: scale(1.55);
      opacity: 0;
    }
  }
  @keyframes link-dash {
    to {
      stroke-dashoffset: -16;
    }
  }
  @keyframes glow-pulse {
    0% {
      transform: scale(1);
      opacity: 0.7;
    }
    100% {
      transform: scale(1.4);
      opacity: 0;
    }
  }
  @keyframes dot-breathe {
    0% {
      box-shadow: 0 0 0 0 rgba(34, 197, 94, 0.5);
    }
    70% {
      box-shadow: 0 0 0 6px rgba(34, 197, 94, 0);
    }
    100% {
      box-shadow: 0 0 0 0 rgba(34, 197, 94, 0);
    }
  }
  @keyframes title-shine {
    0%,
    100% {
      background-position: 0% 50%;
    }
    50% {
      background-position: 100% 50%;
    }
  }
  @keyframes btn-shine {
    to {
      left: 130%;
    }
  }

  /* ================= 自动填充主题修正 ================= */
  :deep(input:-webkit-autofill),
  :deep(input:-webkit-autofill:hover),
  :deep(input:-webkit-autofill:focus) {
    -webkit-text-fill-color: var(--ai-input-text) !important;
    -webkit-box-shadow: 0 0 0 1000px var(--sv-autofill) inset !important;
    caret-color: var(--ai-input-text);
  }

  /* ================= 响应式 ================= */
  @media (max-width: 768px) {
    .ai-stage {
      justify-content: center;
      padding: 24px 16px;
    }
    .ai-card {
      width: 100%;
      padding: 30px 22px 22px;
    }
    .is-hidden-mobile {
      display: none;
    }
    /* 移动端隐藏连线，hub 缩小置于顶部中央，整网退为背景纹理 */
    .ai-links {
      display: none;
    }
    .ai-network {
      opacity: 0.5;
    }
    .ai-hub {
      width: 118px;
      height: 118px;
      left: 50% !important;
      top: 15% !important;
    }
    .ai-hub-core {
      inset: 8px;
    }
    .ai-hub-icon {
      width: 42px;
      height: 42px;
      font-size: 22px;
    }
    .ai-hub-name {
      font-size: 13px;
    }
  }
  @media (max-width: 480px) {
    .ai-bubble:not(.is-hidden-mobile) {
      opacity: 0.65;
    }
  }
  @media (prefers-reduced-motion: reduce) {
    .anim-fade-up,
    .ai-card,
    .ai-bubble,
    .ai-bubble-glow,
    .ai-card-dot,
    .ai-card-title,
    .ai-hub,
    .ai-hub-ring,
    .ai-link-line,
    .ai-link-dot {
      animation: none !important;
      opacity: 1;
    }
  }
</style>

<style lang="less">
@prefix-cls: ~'@{namespace}-mini-login';
@dark-bg: #293146;

html[data-theme='dark'] {
  .@{prefix-cls} {
    /* 深色主题变量覆盖 */
    --ai-glow-a: rgba(99, 102, 241, 0.28);
    --ai-glow-b: rgba(168, 85, 247, 0.24);
    --ai-page-base: linear-gradient(135deg, #0a1028 0%, #101c46 45%, #1e1648 100%);
    --ai-card-bg: linear-gradient(165deg, rgba(30, 41, 82, 0.72), rgba(18, 24, 52, 0.74));
    --ai-card-border: rgba(255, 255, 255, 0.12);
    --ai-card-shadow: 0 30px 80px rgba(3, 8, 30, 0.6), inset 0 1px 0 rgba(255, 255, 255, 0.08);
    --ai-text: rgba(255, 255, 255, 0.9);
    --ai-text-sub: rgba(255, 255, 255, 0.5);
    --ai-text-faint: rgba(255, 255, 255, 0.4);
    --ai-title-gradient: linear-gradient(90deg, #ffffff 0%, #bfdbfe 45%, #ddd6fe 75%, #ffffff 100%);
    --ai-badge-bg: rgba(255, 255, 255, 0.07);
    --ai-badge-border: rgba(255, 255, 255, 0.25);
    --ai-badge-text: rgba(255, 255, 255, 0.85);
    --ai-bubble-bg: linear-gradient(150deg, rgba(255, 255, 255, 0.1), rgba(255, 255, 255, 0.03));
    --ai-bubble-border: rgba(255, 255, 255, 0.16);
    --ai-bubble-shadow: 0 12px 40px rgba(20, 30, 80, 0.35), inset 0 1px 0 rgba(255, 255, 255, 0.15);
    --ai-bubble-text: rgba(255, 255, 255, 0.78);
    --ai-bubble-icon-gradient: linear-gradient(135deg, #93c5fd, #c4b5fd);
    --ai-bubble-hover-border: rgba(165, 180, 252, 0.55);
    --ai-bubble-ring: rgba(129, 140, 248, 0.4);
    /* 光轨连线 */
    --ai-link-stroke: rgba(147, 197, 253, 0.3);
    --ai-link-dot: #93c5fd;
    /* 中心节点 */
    --ai-hub-core-bg: linear-gradient(155deg, rgba(49, 46, 129, 0.9), rgba(30, 27, 75, 0.85));
    --ai-hub-core-border: rgba(165, 180, 252, 0.35);
    --ai-hub-name: rgba(255, 255, 255, 0.92);
    --ai-hub-sub: rgba(165, 180, 252, 0.7);
    --ai-hub-icon-gradient: linear-gradient(135deg, #93c5fd, #d8b4fe);
    --ai-hub-ring: rgba(129, 140, 248, 0.55);
    --ai-tabs-bg: rgba(255, 255, 255, 0.06);
    --ai-tabs-border: rgba(255, 255, 255, 0.08);
    --ai-tabs-inactive: rgba(255, 255, 255, 0.55);
    --ai-field-bg: rgba(255, 255, 255, 0.06);
    --ai-field-border: rgba(255, 255, 255, 0.1);
    --ai-field-focus-bg: rgba(255, 255, 255, 0.09);
    --ai-field-focus-border: rgba(129, 140, 248, 0.7);
    --ai-field-focus-ring: rgba(99, 102, 241, 0.18);
    --ai-field-icon: rgba(255, 255, 255, 0.45);
    --ai-field-icon-focus: #a5b4fc;
    --ai-input-text: #fff;
    --ai-input-placeholder: rgba(255, 255, 255, 0.35);
    --ai-link: #a5b4fc;
    --ai-link-hover: #c7d2fe;
    --ai-checkbox-label: rgba(255, 255, 255, 0.65);
    --ai-checkbox-box-bg: rgba(255, 255, 255, 0.08);
    --ai-checkbox-box-border: rgba(255, 255, 255, 0.3);
    --ai-sms-border: rgba(129, 140, 248, 0.6);
    --ai-sms-bg: rgba(99, 102, 241, 0.16);
    --ai-sms-text: #c7d2fe;
    --ai-sms-disabled-border: rgba(255, 255, 255, 0.15);
    --ai-sms-disabled-text: rgba(255, 255, 255, 0.4);
    --ai-third-title: rgba(255, 255, 255, 0.4);
    --ai-third-line: rgba(255, 255, 255, 0.18);
    --ai-third-item-bg: rgba(255, 255, 255, 0.06);
    --ai-third-item-border: rgba(255, 255, 255, 0.12);
    --ai-third-item-icon: rgba(255, 255, 255, 0.6);
    --sv-track-bg: rgba(255, 255, 255, 0.06);
    --sv-track-border: rgba(255, 255, 255, 0.12);
    --sv-track-hover-border: rgba(129, 140, 248, 0.5);
    --sv-tip: rgba(255, 255, 255, 0.5);
    --sv-tip-icon: rgba(129, 140, 248, 0.8);
    --sv-progress: linear-gradient(90deg, rgba(96, 165, 250, 0.22), rgba(168, 85, 247, 0.28));
    --sv-success-bg: rgba(52, 211, 153, 0.12);
    --sv-success-border: rgba(52, 211, 153, 0.45);
    --sv-success-text: #34d399;
    --sv-autofill: #1a2247;

    .aui-inputClear{
      background-color: #232a3b !important;
    }

    .ant-btn:not(.ant-btn-link):not(.ant-btn-primary) {
      border: 1px solid #4a5569 !important;
    }

    &-form {
      background: @dark-bg !important;
    }

    .app-iconify {
      color: #fff !important;
    }
    .aui-inputClear input,.aui-input-line input,.aui-choice{
      color: #c9d1d9 !important;
    }

    .aui-formBox{
      background-color: @dark-bg !important;
    }
    .aui-third-text span{
      background-color: @dark-bg !important;
    }
    .aui-form-nav .aui-flex-box{
      color: #c9d1d9 !important;
    }

    .aui-formButton .aui-linek-code{
      background:  @dark-bg !important;
      color: white !important;
    }
    .aui-code-line{
      border-left: none !important;
    }
    .ant-checkbox-inner,.aui-success h3{
      border-color: #c9d1d9;
    }
    // 代码逻辑说明: 【QQYUN-6363】这个样式代码有问题，不在里面，导致表达式有问题------------
    &-sign-in-way {
      .anticon {
        font-size: 22px !important;
        color: #888 !important;
        cursor: pointer !important;

        &:hover {
          color: @primary-color !important;
        }
      }
    }
  }

  input.fix-auto-fill,
  .fix-auto-fill input {
    -webkit-text-fill-color: #c9d1d9 !important;
    box-shadow: inherit !important;
  }

  .ant-divider-inner-text {
    font-size: 12px !important;
    color: @text-color-secondary !important;
  }
  .aui-third-login a{
    background: transparent;
  }
}
</style>
