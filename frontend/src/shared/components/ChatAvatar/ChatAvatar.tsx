type Props = {
  icon: string;
  bgClassName?: string;
  className?: string;
};

// 상담 챗봇(봇)·상담 이력(상담원) 대화 화면에서 공통으로 쓰는 참여자 아바타.
// 아이콘·배경색만 바꿔서 재사용한다(고객지원 메뉴 디자인 통일).
export function ChatAvatar({ icon, bgClassName = 'bg-primary', className = '' }: Props) {
  return (
    <div
      className={`flex size-8 shrink-0 items-center justify-center rounded-full ${bgClassName} ${className}`}
    >
      <img src={icon} alt="" className="size-4" aria-hidden="true" />
    </div>
  );
}
