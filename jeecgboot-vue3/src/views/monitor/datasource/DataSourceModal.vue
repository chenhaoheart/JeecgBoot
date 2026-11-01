<template>
  <BasicModal v-bind="$attrs" @register="registerModal" :title="title" @ok="handleSubmit" width="40%">
    <BasicForm @register="registerForm">
      <template #pwd="{ model, field }">
        <a-input-password v-model:value="model[field]" placeholder="请输入密码" autocomplete="new-password" />
        <div v-if="isUpdate" style="color: #ff4d4f; line-height: 1.5; margin-top: 2px">密码留空表示不修改，保存时沿用原密码</div>
      </template>
    </BasicForm>
  </BasicModal>
</template>
<script lang="ts" setup>
  import { ref, computed, unref } from 'vue';
  import { BasicModal, useModalInner } from '/@/components/Modal';
  import { BasicForm, useForm } from '/@/components/Form/index';
  import { formSchema } from './datasource.data';
  import { saveOrUpdateDataSource, getDataSourceById } from './datasource.api';

  // Emits声明
  const emit = defineEmits(['register', 'success']);
  const isUpdate = ref(true);
  //表单配置
  const [registerForm, { resetFields, setFieldsValue, validate }] = useForm({
    // labelWidth: 150,
    schemas: formSchema,
    showActionButtonGroup: false,
  });
  //表单赋值
  const [registerModal, { setModalProps, closeModal }] = useModalInner(async (data) => {
    //重置表单
    await resetFields();
    setModalProps({ confirmLoading: false });
    isUpdate.value = !!data?.isUpdate;
    if (unref(isUpdate)) {
      //获取详情
      data.record = await getDataSourceById({ id: data.record.id });
      //【安全加固】编辑时不回填密码，留空表示不修改；保存由后端按id/code沿用原密码
      await setFieldsValue({
        ...data.record,
        dbPassword: '',
      });
    }
  });
  //设置标题
  const title = computed(() => (!unref(isUpdate) ? '新增数据源' : '编辑数据源'));

  //表单提交事件
  async function handleSubmit(v) {
    try {
      let values = await validate();
      //【安全加固】密码留空表示不修改，由后端沿用原密码保存
      setModalProps({ confirmLoading: true });
      //提交表单
      await saveOrUpdateDataSource(values, isUpdate.value);
      //关闭弹窗
      closeModal();
      //刷新列表
      emit('success');
    } finally {
      setModalProps({ confirmLoading: false });
    }
  }
</script>
