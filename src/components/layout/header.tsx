export function Header () {
  return (
    <div className="page-header">
      <img
        src="/icons/logo.png" 
        alt="Frost Logo" 
        className="frost-logo"
      />
      <div className="title-container">
        <h1>Igloo Mobile</h1>
      </div>
      <p>Enterprise-grade security for the individual.</p>
      <a 
        href="https://frostr.org" 
        target="_blank" 
        rel="noopener noreferrer"
      >
        https://frostr.org
      </a>
      <div className="alpha-pill alpha-pill-standalone">alpha edition</div>
    </div>
  )
}
