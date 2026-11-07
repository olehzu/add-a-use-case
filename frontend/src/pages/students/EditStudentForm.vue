<template>
  <el-form
    ref="studentForm"
    :model="studentBeingEdited"
    :rules="rules"
    label-width="110px"
    style="padding-right: 30px"
    label-position="left"
  >
    <el-form-item label="Id:">
      <el-input v-model="studentBeingEdited.id" disabled></el-input>
    </el-form-item>
    <el-form-item label="Username:" prop="username">
      <el-input v-model="studentBeingEdited.username"></el-input>
    </el-form-item>
    <!-- An active student is deactivated through its own action (UC-STU-deactivate-student) -->
    <el-form-item v-if="loadedStudent?.enabled" label="Status:">
      <el-tag type="success" data-cy="student-status-active">Active</el-tag>
      <el-button
        type="danger"
        plain
        size="small"
        style="margin-left: 12px"
        :loading="deactivating"
        data-cy="deactivate-student"
        @click="confirmDeactivation()"
      >
        Deactivate
      </el-button>
    </el-form-item>
    <!-- A deactivated student is not offered the action (3a); the switch reactivates her until UC-STU-reactivate-student is built -->
    <el-form-item v-else-if="loadedStudent" label="Status:" prop="enabled">
      <el-switch
        v-model="studentBeingEdited.enabled"
        inline-prompt
        active-text="Enabled"
        inactive-text="Disabled"
        style="--el-switch-on-color: #13ce66; --el-switch-off-color: #ff4949"
        data-cy="student-status-switch"
      />
    </el-form-item>
    <el-form-item label="First Name:" prop="firstName">
      <el-input v-model="studentBeingEdited.firstName"></el-input>
    </el-form-item>
    <el-form-item label="Last Name:" prop="lastName">
      <el-input v-model="studentBeingEdited.lastName"></el-input>
    </el-form-item>
    <el-form-item label="Email:" prop="email">
      <el-input v-model="studentBeingEdited.email"></el-input>
    </el-form-item>
    <el-form-item label="Team:" prop="teamName">
      <el-input v-model="studentBeingEdited.teamName" disabled></el-input>
    </el-form-item>
    <div style="display: flex; justify-content: end">
      <el-button @click="$emit('close-dialog')"> Cancel </el-button>
      <el-button type="primary" @click="updateExistingStudent()"> Confirm </el-button>
    </div>
  </el-form>
</template>

<script setup lang="ts">
import axios from 'axios'
import { ElMessage, ElMessageBox } from 'element-plus'
import type { Student } from '@/apis/student/types'
import type { FormInstance } from 'element-plus'
import { deactivateStudent, findStudentById, updateStudent } from '@/apis/student'
import { ref, onMounted } from 'vue'

const { studentId } = defineProps(['studentId'])
const emit = defineEmits(['close-dialog'])

// Note that the structure of the userData is NOT the same as the User interface since the roles are stored as an array
const studentBeingEdited = ref<Student>({
  // Data of the user being added or edited
  id: NaN,
  username: '',
  enabled: false,
  firstName: '',
  lastName: '',
  email: ''
})

const studentForm = ref<FormInstance>() // Form reference

// The student as last loaded from the server: decides which status control is shown, and names her in the warning
// even if the name fields have been edited since
const loadedStudent = ref<Student>()
const deactivating = ref(false)

// Validation rules
const rules = {
  username: [
    { required: true, message: 'Please input your email as your username', trigger: 'blur' },
    {
      pattern: /^[^\s@]+@[^\s@]+\.[^\s@]+$/,
      message: 'Enter your email as username.',
      trigger: 'blur'
    }
  ],
  enabled: [{ required: true, message: 'Please select the status of the user.', trigger: 'blur' }],
  firstName: [
    { required: true, message: 'Please provide the first name of the user.', trigger: 'blur' }
  ],
  lastName: [
    { required: true, message: 'Please provide the last name of the user.', trigger: 'blur' }
  ],
  email: [
    { required: true, message: 'Please provide the email of the user.', trigger: 'blur' },
    { type: 'email', message: 'Please provide a valid email.', trigger: 'blur' }
  ]
}

async function loadStudent(studentId: number) {
  // Load the user to be edited
  const result = await findStudentById(studentId)
  loadedStudent.value = result.data

  studentBeingEdited.value.id = result.data.id as number
  studentBeingEdited.value.username = result.data.username
  studentBeingEdited.value.enabled = result.data.enabled
  studentBeingEdited.value.firstName = result.data.firstName
  studentBeingEdited.value.lastName = result.data.lastName
  studentBeingEdited.value.email = result.data.email
  studentBeingEdited.value.teamName = result.data.teamName
}

onMounted(() => {
  loadStudent(studentId) // Load the artifact to be edited
})

// UC-STU-deactivate-student, steps 3 to 6
async function confirmDeactivation() {
  const { firstName, lastName, id } = loadedStudent.value!
  try {
    // Step 4: the consequences, and a confirmation
    await ElMessageBox.confirm(
      `${firstName} will no longer be able to log in, and a session already open ends at the next action. ` +
        `${firstName} will no longer owe weekly activity reports or peer evaluations and will get no reminders. ` +
        `Teammates will no longer be asked to rate ${firstName}, and can no longer submit or change a peer evaluation of ${firstName}. ` +
        `${firstName} stays in the course section and on the team, and all weekly activity reports and peer evaluations are kept. ` +
        `You can reactivate ${firstName} later.`,
      `Deactivate ${firstName} ${lastName}?`,
      { confirmButtonText: 'Deactivate', cancelButtonText: 'Cancel', type: 'warning' }
    )
  } catch {
    return // 5a: cancelled, nothing is sent and the dialog stays on the student
  }

  deactivating.value = true
  try {
    await deactivateStudent(id as number)
    ElMessage.success(`${firstName} ${lastName} has been deactivated.`) // Step 6
    emit('close-dialog') // Students.vue reloads the list
  } catch (error) {
    // request.ts has already shown the server's message. On 6a, reload her so the action is no longer offered (3a)
    if (axios.isAxiosError(error) && error.response?.status === 409) {
      await loadStudent(id as number)
    }
  } finally {
    deactivating.value = false
  }
}

async function updateExistingStudent() {
  await studentForm.value!.validate() // Validate the form

  const updatedStudent: Student = {
    id: studentBeingEdited.value.id,
    username: studentBeingEdited.value.username,
    enabled: studentBeingEdited.value.enabled,
    firstName: studentBeingEdited.value.firstName,
    lastName: studentBeingEdited.value.lastName,
    email: studentBeingEdited.value.email
  }
  console.log(updatedStudent)
  // Call the API to update the user
  await updateStudent(updatedStudent)

  ElMessage.success('Student updated successfully')
  emit('close-dialog')
}
</script>

<style scoped lang="scss"></style>
