db = db.getSiblingDB('voicebot');

db.intent_playbook.insertMany([
  {
    _id: "인사", intent: "인사",
    response: "안녕하세요! 무엇을 도와드릴까요?",
    action: "provide_info", escalate: false, confidenceThreshold: 0.7
  },
  {
    _id: "배송문의", intent: "배송문의",
    response: "배송 관련 안내드립니다. 일반 배송은 결제 완료 후 2~3 영업일, 특급 배송은 1 영업일 이내 도착합니다. 배송 조회는 주문번호를 말씀해주세요.",
    action: "provide_info", escalate: false, confidenceThreshold: 0.7
  },
  {
    _id: "반품환불", intent: "반품환불",
    response: "반품과 환불은 수령 후 7일 이내 신청 가능합니다. 주문번호와 사유를 알려주시면 처리해드리겠습니다.",
    action: "provide_info", escalate: false, confidenceThreshold: 0.7
  },
  {
    _id: "교환", intent: "교환",
    response: "교환은 수령 후 7일 이내, 상품 하자 또는 오배송 시 가능합니다. 주문번호와 교환 사유를 알려주시면 처리해드리겠습니다.",
    action: "provide_info", escalate: false, confidenceThreshold: 0.7
  },
  {
    _id: "결제", intent: "결제",
    response: "결제는 신용카드, 계좌이체, 무통장입금이 가능합니다. 결제 관련 문의는 주문번호를 알려주시면 확인해드리겠습니다.",
    action: "provide_info", escalate: false, confidenceThreshold: 0.7
  },
  {
    _id: "회원", intent: "회원",
    response: "회원정보 변경, 탈퇴, 비밀번호 재설정은 마이페이지에서 처리하실 수 있습니다. 추가 도움이 필요하시면 말씀해주세요.",
    action: "provide_info", escalate: false, confidenceThreshold: 0.7
  },
  {
    _id: "주문조회", intent: "주문조회",
    response: "주문번호를 말씀해주시면 주문 상태를 확인해드리겠습니다.",
    action: "request_order_number", escalate: false, confidenceThreshold: 0.7
  },
  {
    _id: "상담원연결", intent: "상담원연결",
    response: "상담원에게 연결해드리겠습니다. 잠시만 기다려주세요.",
    action: "escalate", escalate: true, confidenceThreshold: 0.7
  },
  {
    _id: "종료", intent: "종료",
    response: "이용해 주셔서 감사합니다. 좋은 하루 되세요.",
    action: "end_call", escalate: false, confidenceThreshold: 0.7
  },
  {
    _id: "기타", intent: "기타",
    response: "죄송합니다. 잠시 후 상담원을 연결해드리겠습니다.",
    action: "fallback", escalate: false, confidenceThreshold: 0.7
  }
]);

print("Playbook 초기 데이터 투입 완료: " + db.intent_playbook.countDocuments() + "건");
