variable "image_tag" {
  description = "실험할 커밋의 해시 7자리. GitHub Actions 로 이미지를 먼저 만들어 둬야 한다."
  type        = string

  validation {
    condition     = can(regex("^[0-9a-f]{7}$", var.image_tag))
    error_message = "image_tag 는 커밋 해시 7자리여야 합니다."
  }
}

variable "ssh_public_key_path" {
  description = "서버 접속에 쓸 SSH 공개 키 파일 경로"
  type        = string
}

variable "second_app" {
  description = "여러 서버의 한도 공유를 볼 때만 true 로 두어 app 을 1대 더 띄운다."
  type        = bool
  default     = false
}

variable "region" {
  description = "AWS 리전"
  type        = string
  default     = "ap-northeast-2"
}

variable "availability_zone" {
  description = "모든 서버를 둘 가용 영역"
  type        = string
  default     = "ap-northeast-2a"
}

variable "repository" {
  description = "서버가 설정 파일을 받아 올 GitHub 저장소"
  type        = string
  default     = "testerhyuk/api_flow_control"
}

variable "image_repository" {
  description = "컨테이너 이미지 경로의 앞부분"
  type        = string
  default     = "ghcr.io/testerhyuk/api_flow_control"
}
